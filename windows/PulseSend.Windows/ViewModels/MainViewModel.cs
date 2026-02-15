using System.Collections.ObjectModel;
using System;
using Avalonia.Threading;
using System.Diagnostics;
using System.IO;
using PulseSend.Core.Discovery;
using PulseSend.Core.Models;
using PulseSend.Core.Network;
using PulseSend.Core.Services;
using PulseSend.Core.Storage;
using System.Collections.Concurrent;

namespace PulseSend.Windows.ViewModels;

public sealed class MainViewModel : ViewModelBase
{
    private readonly DeviceIdentity _identity;
    private readonly TrustedDeviceRegistry _registry;
    private readonly ServerHost _server;
    private readonly DiscoveryScanner _discovery;
    private readonly SecureTransport _transport;
    private readonly TransferClient _transferClient;

    private readonly Dictionary<string, DeviceViewModel> _deviceIndex = new();
    private readonly Dictionary<string, TransferItemViewModel> _transferIndex = new();
    private readonly Dictionary<string, MessageItemViewModel> _messageIndex = new();
    private readonly HashSet<string> _pendingUploadPaths = new(StringComparer.OrdinalIgnoreCase);
    private readonly Dictionary<string, CancellationTokenSource> _heartbeats = new();
    private readonly ConcurrentDictionary<string, int> _heartbeatFailures = new();
    private readonly DispatcherTimer _onlineTimer;
    private static readonly TimeSpan OfflineGraceWindow = TimeSpan.FromSeconds(8);
    private const int OfflineFailureThreshold = 3;

    private string _pairCode = "------";
    private string _statusText = "启动中";
    private string _fingerprint = "";
    private int _port;
    private string _notice = "";
    private string _messageDraft = "";
    private string _manualAddress = "";
    private string _manualPortText = "48084";
    private string _incomingFolder = "";
    private DeviceViewModel? _selectedDevice;

    public ObservableCollection<DeviceViewModel> Devices { get; } = new();
    public ObservableCollection<TransferItemViewModel> Transfers { get; } = new();
    public ObservableCollection<MessageItemViewModel> Messages { get; } = new();
    public ObservableCollection<PendingUploadItemViewModel> PendingUploads { get; } = new();

    public Func<Task<string?>>? PairCodePrompt { get; set; }
    public Func<Task<string?>>? FilePicker { get; set; }
    public Func<Task<string?>>? FolderPicker { get; set; }
    public Func<string, Task>? CopyTextRequested { get; set; }
    public Func<string, Task>? FullTextRequested { get; set; }

    public RelayCommand RegenerateCodeCommand { get; }
    public AsyncCommand PickIncomingFolderCommand { get; }
    public AsyncCommand PairSelectedCommand { get; }
    public AsyncCommand SendFileCommand { get; }
    public AsyncCommand SendQueuedFilesCommand { get; }
    public AsyncCommand SendMessageCommand { get; }
    public RelayCommand AddManualDeviceCommand { get; }

    public Action<string>? AlertMessageRequested { get; set; }

    public MainViewModel()
    {
        var identityStore = new DeviceIdentityStore();
        _identity = identityStore.Load(Environment.MachineName, "windows");

        _registry = new TrustedDeviceRegistry(new TrustedDeviceStore());
        _server = new ServerHost(_identity, _registry);
        _transport = new SecureTransport(_registry);
        _transferClient = new TransferClient(_transport, _identity);
        _discovery = new DiscoveryScanner(_identity);
        _discovery.DeviceDiscovered += OnDeviceDiscovered;

        RegenerateCodeCommand = new RelayCommand(() => _server.RegeneratePairCode());
        PickIncomingFolderCommand = new AsyncCommand(PickIncomingFolderAsync);
        PairSelectedCommand = new AsyncCommand(PairSelectedAsync, () => HasSelectedDevice);
        SendFileCommand = new AsyncCommand(SendFileAsync, () => HasSelectedDevice);
        SendQueuedFilesCommand = new AsyncCommand(SendQueuedFilesAsync, () => HasSelectedDevice && HasPendingUploads);
        SendMessageCommand = new AsyncCommand(SendMessageAsync, () => HasSelectedDevice && !string.IsNullOrWhiteSpace(MessageDraft));
        AddManualDeviceCommand = new RelayCommand(AddManualDevice, () => !string.IsNullOrWhiteSpace(ManualAddress));

        _server.SnapshotUpdated += snapshot =>
        {
            Dispatcher.UIThread.Post(() => ApplySnapshot(snapshot));
        };
        _server.TransferError += message =>
        {
            Dispatcher.UIThread.Post(() =>
            {
                Notice = message;
                AlertMessageRequested?.Invoke(message);
            });
        };
        _incomingFolder = _server.IncomingFolder;

        RefreshTrustedDevices();
        _onlineTimer = new DispatcherTimer
        {
            Interval = TimeSpan.FromSeconds(5)
        };
        _onlineTimer.Tick += (_, _) => RefreshOnlineFlags();
        _onlineTimer.Start();
        _ = _server.StartAsync();
        _discovery.Start();
    }

    public string PairCode
    {
        get => _pairCode;
        private set => SetField(ref _pairCode, value);
    }

    public string StatusText
    {
        get => _statusText;
        private set => SetField(ref _statusText, value);
    }

    public string Fingerprint
    {
        get => _fingerprint;
        private set => SetField(ref _fingerprint, value);
    }

    public int Port
    {
        get => _port;
        private set => SetField(ref _port, value);
    }

    public string Notice
    {
        get => _notice;
        private set => SetField(ref _notice, value);
    }

    public string MessageDraft
    {
        get => _messageDraft;
        set
        {
            if (SetField(ref _messageDraft, value))
            {
                SendMessageCommand.NotifyCanExecuteChanged();
            }
        }
    }

    public string ManualAddress
    {
        get => _manualAddress;
        set
        {
            if (SetField(ref _manualAddress, value))
            {
                AddManualDeviceCommand.NotifyCanExecuteChanged();
            }
        }
    }

    public string ManualPortText
    {
        get => _manualPortText;
        set => SetField(ref _manualPortText, value);
    }

    public string IncomingFolder
    {
        get => _incomingFolder;
        private set => SetField(ref _incomingFolder, value);
    }

    public DeviceViewModel? SelectedDevice
    {
        get => _selectedDevice;
        set
        {
            if (SetField(ref _selectedDevice, value))
            {
                RaisePropertyChanged(nameof(HasSelectedDevice));
                PairSelectedCommand.NotifyCanExecuteChanged();
                SendFileCommand.NotifyCanExecuteChanged();
                SendQueuedFilesCommand.NotifyCanExecuteChanged();
                SendMessageCommand.NotifyCanExecuteChanged();
            }
        }
    }

    public bool HasSelectedDevice => SelectedDevice != null;
    public bool HasPendingUploads => PendingUploads.Count > 0;

    public void EnqueueFiles(IEnumerable<string> filePaths)
    {
        var added = 0;
        foreach (var path in filePaths)
        {
            if (string.IsNullOrWhiteSpace(path))
            {
                continue;
            }
            var fullPath = Path.GetFullPath(path);
            if (!File.Exists(fullPath) || !_pendingUploadPaths.Add(fullPath))
            {
                continue;
            }
            PendingUploads.Add(new PendingUploadItemViewModel(fullPath, RemovePendingUpload));
            added++;
        }
        if (added > 0)
        {
            RaisePropertyChanged(nameof(HasPendingUploads));
            SendFileCommand.NotifyCanExecuteChanged();
            SendQueuedFilesCommand.NotifyCanExecuteChanged();
            Notice = $"已加入待上传队列：{added} 个文件";
        }
    }

    private void RemovePendingUpload(PendingUploadItemViewModel item)
    {
        if (item == null)
        {
            return;
        }
        if (PendingUploads.Remove(item))
        {
            _pendingUploadPaths.Remove(item.FilePath);
            RaisePropertyChanged(nameof(HasPendingUploads));
            SendFileCommand.NotifyCanExecuteChanged();
            SendQueuedFilesCommand.NotifyCanExecuteChanged();
            Notice = $"已从队列移除：{item.FileName}";
        }
    }

    private void OnDeviceDiscovered(DeviceInfo info)
    {
        _heartbeatFailures[info.Id] = 0;
        Dispatcher.UIThread.Post(() => UpsertDevice(info));
        EnsureHeartbeat(info.Id);
    }

    private void ApplySnapshot(ServerSnapshot snapshot)
    {
        PairCode = snapshot.PairCode;
        Fingerprint = snapshot.Fingerprint;
        StatusText = snapshot.StatusText;
        Port = snapshot.Port;

        foreach (var transfer in snapshot.Transfers)
        {
            UpsertTransfer(transfer);
        }

        foreach (var message in snapshot.Messages)
        {
            UpsertMessage(message);
        }

        RefreshTrustedDevices();
    }

    private void UpsertDevice(DeviceInfo info)
    {
        var record = _registry.FindById(info.Id);
        if (_deviceIndex.TryGetValue(info.Id, out var existing))
        {
            existing.Update(info, record);
            return;
        }
        var viewModel = new DeviceViewModel();
        viewModel.Update(info, record);
        _deviceIndex[info.Id] = viewModel;
        Devices.Add(viewModel);
    }

    private void EnsureHeartbeat(string deviceId)
    {
        if (_heartbeats.ContainsKey(deviceId))
        {
            return;
        }
        var cts = new CancellationTokenSource();
        _heartbeats[deviceId] = cts;
        _ = Task.Run(async () =>
        {
            while (!cts.IsCancellationRequested)
            {
                try
                {
                    var viewModel = await Dispatcher.UIThread.InvokeAsync(() =>
                    {
                        _deviceIndex.TryGetValue(deviceId, out var vm);
                        return vm;
                    });
                    if (viewModel != null)
                    {
                        var info = viewModel.ToDeviceInfo();
                        if (!string.IsNullOrWhiteSpace(info.Address))
                        {
                            var ok = await _transport.PingAsync(info);
                            if (ok)
                            {
                                _heartbeatFailures[deviceId] = 0;
                                Dispatcher.UIThread.Post(viewModel.Touch);
                            }
                            else
                            {
                                var failures = _heartbeatFailures.AddOrUpdate(deviceId, 1, (_, prev) => prev + 1);
                                var recentlySeen = DateTime.Now - viewModel.LastSeen <= OfflineGraceWindow;
                                if (!recentlySeen && failures >= OfflineFailureThreshold)
                                {
                                    Dispatcher.UIThread.Post(() => HandleOffline(viewModel));
                                }
                            }
                        }
                    }
                }
                catch
                {
                    // ignore heartbeat failures
                }
                await Task.Delay(TimeSpan.FromSeconds(2), cts.Token);
            }
        }, cts.Token);
    }

    private void RefreshTrustedDevices()
    {
        foreach (var record in _registry.GetAll())
        {
            _deviceIndex.TryGetValue(record.DeviceId, out var existing);
            var address = !string.IsNullOrWhiteSpace(record.LastSeenAddress)
                ? record.LastSeenAddress!
                : existing?.Address ?? string.Empty;
            var port = record.LastSeenPort ?? existing?.TlsPort ?? 48084;
            var lastSeen = existing?.LastSeen ?? DateTime.MinValue;
            var info = new DeviceInfo
            {
                Id = record.DeviceId,
                Name = record.DeviceName,
                Platform = "android",
                Address = address,
                TlsPort = port,
                Fingerprint = record.Fingerprint,
                IsTrusted = true,
                LastSeen = lastSeen
            };
            UpsertDevice(info);
        }
    }

    private void RefreshOnlineFlags()
    {
        foreach (var device in Devices)
        {
            var wasOnline = device.IsOnline;
            device.RefreshOnlineStatus();
            if (wasOnline && !device.IsOnline)
            {
                HandleOffline(device);
            }
        }
    }

    private void HandleOffline(DeviceViewModel device)
    {
        if (!string.IsNullOrWhiteSpace(device.Id))
        {
            _heartbeatFailures[device.Id] = OfflineFailureThreshold;
        }
        device.MarkOffline();
    }

    private void UpsertTransfer(TransferViewItem item)
    {
        if (_transferIndex.TryGetValue(item.TransferId, out var existing))
        {
            existing.UpdateFrom(item);
            return;
        }
        var viewModel = new TransferItemViewModel();
        viewModel.UpdateFrom(item);
        viewModel.OpenRequested = OpenFile;
        viewModel.DeleteRequested = DeleteTransferFile;
        _transferIndex[item.TransferId] = viewModel;
        Transfers.Add(viewModel);
    }

    private void UpsertMessage(MessageViewItem item)
    {
        if (_messageIndex.TryGetValue(item.MessageId, out var existing))
        {
            existing.UpdateFrom(item);
            return;
        }
        var viewModel = new MessageItemViewModel();
        viewModel.UpdateFrom(item);
        viewModel.CopyRequested = text =>
        {
            var copyAction = CopyTextRequested;
            if (copyAction == null)
            {
                return;
            }
            _ = copyAction(text);
        };
        viewModel.ViewFullRequested = text =>
        {
            var fullTextAction = FullTextRequested;
            if (fullTextAction == null)
            {
                return;
            }
            _ = fullTextAction(text);
        };
        _messageIndex[item.MessageId] = viewModel;
        Messages.Insert(0, viewModel);
        if (Messages.Count > 50)
        {
            Messages.RemoveAt(Messages.Count - 1);
        }
    }

    private async Task PairSelectedAsync()
    {
        if (SelectedDevice == null)
        {
            return;
        }
        if (PairCodePrompt == null)
        {
            Notice = "无法打开配对窗口。";
            return;
        }
        var code = await PairCodePrompt();
        if (string.IsNullOrWhiteSpace(code))
        {
            return;
        }
        try
        {
            var info = SelectedDevice.ToDeviceInfo();
            await _transport.PairAsync(info, _identity, code.Trim());
            Notice = "配对成功。";
            RefreshTrustedDevices();
            if (SelectedDevice != null)
            {
                var refreshed = _registry.FindById(SelectedDevice.Id);
                SelectedDevice.Update(SelectedDevice.ToDeviceInfo(), refreshed);
            }
        }
        catch (Exception ex)
        {
            Notice = $"配对失败：{ex.Message}";
        }
    }

    private async Task SendFileAsync()
    {
        if (SelectedDevice == null)
        {
            return;
        }
        if (!await EnsureSelectedDeviceOnlineForSendingAsync())
        {
            return;
        }
        if (PendingUploads.Count > 0)
        {
            await SendQueuedFilesAsync();
            return;
        }
        if (FilePicker == null)
        {
            Notice = "无法打开文件选择器。";
            return;
        }
        var filePath = await FilePicker();
        if (string.IsNullOrWhiteSpace(filePath))
        {
            return;
        }
        await SendFilePathAsync(filePath);
    }

    private async Task SendQueuedFilesAsync()
    {
        if (SelectedDevice == null)
        {
            return;
        }
        if (!await EnsureSelectedDeviceOnlineForSendingAsync())
        {
            return;
        }
        if (PendingUploads.Count == 0)
        {
            Notice = "待上传队列为空";
            return;
        }
        var queued = PendingUploads.ToList();
        foreach (var item in queued)
        {
            var sent = await SendFilePathAsync(item.FilePath);
            if (!sent)
            {
                break;
            }
            _pendingUploadPaths.Remove(item.FilePath);
            PendingUploads.Remove(item);
        }
        RaisePropertyChanged(nameof(HasPendingUploads));
        SendFileCommand.NotifyCanExecuteChanged();
        SendQueuedFilesCommand.NotifyCanExecuteChanged();
        if (PendingUploads.Count == 0)
        {
            Notice = "待上传队列已发送完成";
        }
    }

    private async Task<bool> SendFilePathAsync(string filePath)
    {
        if (SelectedDevice == null)
        {
            return false;
        }
        try
        {
            var info = SelectedDevice.ToDeviceInfo();
            await _transferClient.SendFileAsync(info, filePath, progress =>
            {
                Dispatcher.UIThread.Post(() =>
                {
                    var item = new TransferViewItem
                    {
                        TransferId = progress.TransferId,
                        FileName = progress.FileName,
                        TotalBytes = progress.TotalBytes,
                        ReceivedBytes = progress.SentBytes,
                        StatusText = progress.StatusText,
                        Direction = TransferDirection.Outgoing,
                        SavedPath = filePath,
                        UpdatedAt = DateTime.Now
                    };
                    UpsertTransfer(item);
                });
            });
            Notice = "文件发送完成。";
            return true;
        }
        catch (Exception ex)
        {
            if (IsConnectionRefused(ex))
            {
                if (SelectedDevice != null)
                {
                    HandleOffline(SelectedDevice);
                }
                Notice = "发送失败：目标计算机拒绝连接（已标记离线）。";
            }
            else if (IsUnauthorized(ex))
            {
                HandlePairingDesync(SelectedDevice);
                Notice = "发送失败：对方未授权当前设备，请重新配对。";
            }
            else
            {
                Notice = $"发送失败：{ex.Message}";
            }
            return false;
        }
    }
    private async Task SendMessageAsync()
    {
        if (SelectedDevice == null)
        {
            return;
        }
        if (!await EnsureSelectedDeviceOnlineForSendingAsync())
        {
            return;
        }
        if (string.IsNullOrWhiteSpace(MessageDraft))
        {
            return;
        }
        try
        {
            var text = MessageDraft.Trim();
            var info = SelectedDevice.ToDeviceInfo();
            await _transferClient.SendMessageAsync(info, text);
            MessageDraft = string.Empty;
            var message = new MessageViewItem
            {
                MessageId = Guid.NewGuid().ToString("N"),
                DeviceName = info.Name,
                Content = text,
                ReceivedAt = DateTime.Now,
                Direction = MessageDirection.Outgoing
            };
            UpsertMessage(message);
            Notice = "消息已发送。";
        }
        catch (Exception ex)
        {
            if (IsConnectionRefused(ex))
            {
                if (SelectedDevice != null)
                {
                    HandleOffline(SelectedDevice);
                }
                Notice = "消息发送失败：目标计算机拒绝连接（已标记离线）。";
            }
            else if (IsUnauthorized(ex))
            {
                HandlePairingDesync(SelectedDevice);
                Notice = "消息发送失败：对方未授权当前设备，请重新配对。";
            }
            else
            {
                Notice = $"消息发送失败：{ex.Message}";
            }
        }
    }

    private async Task PickIncomingFolderAsync()
    {
        if (FolderPicker == null)
        {
            Notice = "无法打开文件夹选择器。";
            return;
        }
        var folder = await FolderPicker();
        if (string.IsNullOrWhiteSpace(folder))
        {
            return;
        }
        _server.SetIncomingFolder(folder);
        IncomingFolder = _server.IncomingFolder;
        Notice = $"已切换接收目录：{IncomingFolder}";
    }

    private void AddManualDevice()
    {
        if (string.IsNullOrWhiteSpace(ManualAddress))
        {
            return;
        }
        var port = 48084;
        if (int.TryParse(ManualPortText, out var parsed))
        {
            port = parsed;
        }
        var info = new DeviceInfo
        {
            Id = $"manual-{Guid.NewGuid():N}",
            Name = "手动设备",
            Platform = "manual",
            Address = ManualAddress.Trim(),
            TlsPort = port,
            LastSeen = DateTime.Now
        };
        UpsertDevice(info);
        ManualAddress = string.Empty;
        ManualPortText = "48084";
        Notice = "已添加手动设备。";
    }

    private static bool IsConnectionRefused(Exception ex)
    {
        var message = ex.Message ?? string.Empty;
        return message.Contains("actively refused", StringComparison.OrdinalIgnoreCase)
            || message.Contains("No connection could be made", StringComparison.OrdinalIgnoreCase);
    }

    private static bool IsUnauthorized(Exception ex)
    {
        var message = ex.Message ?? string.Empty;
        return message.Contains("401", StringComparison.OrdinalIgnoreCase)
            || message.Contains("unauthorized", StringComparison.OrdinalIgnoreCase)
            || message.Contains("未授权", StringComparison.OrdinalIgnoreCase)
            || message.Contains("未配对", StringComparison.OrdinalIgnoreCase)
            || message.Contains("not paired", StringComparison.OrdinalIgnoreCase);
    }

    private void HandlePairingDesync(DeviceViewModel? device)
    {
        if (device == null || string.IsNullOrWhiteSpace(device.Id))
        {
            return;
        }
        _registry.ResetTokens(device.Id);
        RefreshTrustedDevices();
    }

    private void OpenFile(string path)
    {
        if (string.IsNullOrWhiteSpace(path) || !File.Exists(path))
        {
            Notice = "文件不存在或已被移动。";
            return;
        }
        try
        {
            Process.Start(new ProcessStartInfo
            {
                FileName = path,
                UseShellExecute = true
            });
        }
        catch (Exception ex)
        {
            Notice = $"无法打开文件：{ex.Message}";
        }
    }

    private Task<bool> EnsureSelectedDeviceOnlineForSendingAsync()
    {
        if (SelectedDevice == null)
        {
            return Task.FromResult(false);
        }
        if (SelectedDevice.IsOnline)
        {
            return Task.FromResult(true);
        }
        var text = "对方设备已下线，请等待其上线后再发送。";
        Notice = text;
        AlertMessageRequested?.Invoke(text);
        return Task.FromResult(false);
    }

    private void DeleteTransferFile(TransferItemViewModel item)
    {
        if (item == null || string.IsNullOrWhiteSpace(item.SavedPath))
        {
            return;
        }
        var path = item.SavedPath;
        try
        {
            if (!File.Exists(path))
            {
                Notice = "删除失败：文件不存在";
                return;
            }
            File.Delete(path);
            Transfers.Remove(item);
            if (!string.IsNullOrWhiteSpace(item.TransferId))
            {
                _transferIndex.Remove(item.TransferId);
            }
            Notice = $"已删除：{item.FileName}";
        }
        catch (Exception ex)
        {
            Notice = $"删除失败：{ex.Message}";
        }
    }
}








