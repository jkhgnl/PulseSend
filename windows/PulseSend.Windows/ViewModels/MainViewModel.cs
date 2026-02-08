using System.Collections.ObjectModel;
using System;
using Avalonia.Threading;
using PulseSend.Core.Discovery;
using PulseSend.Core.Models;
using PulseSend.Core.Network;
using PulseSend.Core.Services;
using PulseSend.Core.Storage;

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
    private readonly Dictionary<string, CancellationTokenSource> _heartbeats = new();
    private readonly DispatcherTimer _onlineTimer;

    private string _pairCode = "------";
    private string _statusText = "启动中";
    private string _fingerprint = "";
    private int _port;
    private string _notice = "";
    private string _messageDraft = "";
    private string _manualAddress = "";
    private string _manualPortText = "48084";
    private DeviceViewModel? _selectedDevice;

    public ObservableCollection<DeviceViewModel> Devices { get; } = new();
    public ObservableCollection<TransferItemViewModel> Transfers { get; } = new();
    public ObservableCollection<MessageItemViewModel> Messages { get; } = new();

    public Func<Task<string?>>? PairCodePrompt { get; set; }
    public Func<Task<string?>>? FilePicker { get; set; }

    public RelayCommand RegenerateCodeCommand { get; }
    public AsyncCommand PairSelectedCommand { get; }
    public AsyncCommand SendFileCommand { get; }
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
        PairSelectedCommand = new AsyncCommand(PairSelectedAsync, () => HasSelectedDevice);
        SendFileCommand = new AsyncCommand(SendFileAsync, () => HasSelectedDevice);
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
                SendMessageCommand.NotifyCanExecuteChanged();
            }
        }
    }

    public bool HasSelectedDevice => SelectedDevice != null;

    private void OnDeviceDiscovered(DeviceInfo info)
    {
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
                                Dispatcher.UIThread.Post(viewModel.Touch);
                            }
                            else
                            {
                                Dispatcher.UIThread.Post(() => HandleOffline(viewModel));
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
        device.MarkOffline();
        _registry.ResetTokens(device.Id);
        device.ClearPairState();
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
                        Direction = TransferDirection.Outgoing
                    };
                    UpsertTransfer(item);
                });
            });
            Notice = "文件发送完成。";
        }
        catch (Exception ex)
        {
            if (IsConnectionRefused(ex))
            {
                if (SelectedDevice != null)
                {
                    HandleOffline(SelectedDevice);
                }
                Notice = "发送失败：目标计算机积极拒绝，无法连接（已标记离线）。";
            }
            else
            {
                Notice = $"发送失败：{ex.Message}";
            }
        }
    }

    private async Task SendMessageAsync()
    {
        if (SelectedDevice == null)
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
                Notice = "消息发送失败：目标计算机积极拒绝，无法连接（已标记离线）。";
            }
            else
            {
                Notice = $"消息发送失败：{ex.Message}";
            }
        }
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
            || message.Contains("积极拒绝", StringComparison.OrdinalIgnoreCase)
            || message.Contains("No connection could be made", StringComparison.OrdinalIgnoreCase);
    }
}





