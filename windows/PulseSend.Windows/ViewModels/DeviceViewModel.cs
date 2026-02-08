using PulseSend.Core.Models;

namespace PulseSend.Windows.ViewModels;

public sealed class DeviceViewModel : ViewModelBase
{
    private string _id = "";
    private string _name = "";
    private string _platform = "";
    private string _address = "";
    private int _tlsPort;
    private string? _fingerprint;
    private bool _canSend;
    private bool _canReceive;
    private bool _isOnline;
    private DateTime _lastSeen;

    public string Id
    {
        get => _id;
        private set => SetField(ref _id, value);
    }

    public string Name
    {
        get => _name;
        private set => SetField(ref _name, value);
    }

    public string Platform
    {
        get => _platform;
        private set => SetField(ref _platform, value);
    }

    public string Address
    {
        get => _address;
        private set
        {
            if (SetField(ref _address, value))
            {
                RaisePropertyChanged(nameof(DisplayAddress));
            }
        }
    }

    public int TlsPort
    {
        get => _tlsPort;
        private set
        {
            if (SetField(ref _tlsPort, value))
            {
                RaisePropertyChanged(nameof(DisplayAddress));
                RaisePropertyChanged(nameof(PortLabel));
            }
        }
    }

    public string? Fingerprint
    {
        get => _fingerprint;
        private set
        {
            if (SetField(ref _fingerprint, value))
            {
                RaisePropertyChanged(nameof(FingerprintLabel));
            }
        }
    }

    public string FingerprintLabel => string.IsNullOrWhiteSpace(Fingerprint)
        ? "指纹：未获取"
        : $"指纹：{Fingerprint}";

    public string PortLabel => TlsPort > 0
        ? $"端口：{TlsPort}"
        : "端口：待发现";

    public bool CanSend
    {
        get => _canSend;
        private set
        {
            if (SetField(ref _canSend, value))
            {
                RaisePropertyChanged(nameof(TrustLabel));
                RaisePropertyChanged(nameof(StatusLabel));
                RaisePropertyChanged(nameof(PairLabel));
            }
        }
    }

    public bool CanReceive
    {
        get => _canReceive;
        private set
        {
            if (SetField(ref _canReceive, value))
            {
                RaisePropertyChanged(nameof(TrustLabel));
                RaisePropertyChanged(nameof(StatusLabel));
                RaisePropertyChanged(nameof(PairLabel));
            }
        }
    }

    public DateTime LastSeen
    {
        get => _lastSeen;
        private set
        {
            if (SetField(ref _lastSeen, value))
            {
                UpdateOnlineStatus();
            }
        }
    }

    public string DisplayAddress => string.IsNullOrWhiteSpace(Address)
        ? "未提供地址"
        : TlsPort > 0 ? $"{Address}:{TlsPort}" : Address;

    public string TrustLabel => StatusLabel;

    public string OnlineLabel => IsOnline ? "在线" : "离线";

    public Avalonia.Media.Brush OnlineBrush => IsOnline
        ? new Avalonia.Media.SolidColorBrush(Avalonia.Media.Color.Parse("#1F9D6A"))
        : new Avalonia.Media.SolidColorBrush(Avalonia.Media.Color.Parse("#D64045"));

    public string PairLabel
    {
        get
        {
            if (!IsOnline)
            {
                return "未配对";
            }
            if (CanSend && CanReceive)
            {
                return "双向已配对";
            }
            if (CanSend)
            {
                return "可发送";
            }
            if (CanReceive)
            {
                return "已授权对方";
            }
            return "未配对";
        }
    }

    public string StatusLabel
    {
        get
        {
            var baseLabel = "未配对";
            if (CanSend && CanReceive)
            {
                baseLabel = "双向已配对";
            }
            else if (CanSend)
            {
                baseLabel = "可发送";
            }
            else if (CanReceive)
            {
                baseLabel = "已授权对方";
            }
            return IsOnline ? baseLabel : $"{baseLabel}（离线）";
        }
    }

    public bool IsOnline
    {
        get => _isOnline;
        private set
        {
            if (SetField(ref _isOnline, value))
            {
                RaisePropertyChanged(nameof(StatusLabel));
                RaisePropertyChanged(nameof(OnlineLabel));
                RaisePropertyChanged(nameof(OnlineBrush));
                RaisePropertyChanged(nameof(PairLabel));
            }
        }
    }

    public void Update(DeviceInfo info, DeviceRecord? record)
    {
        Id = info.Id;
        Name = string.IsNullOrWhiteSpace(info.Name) ? "未知设备" : info.Name;
        Platform = string.IsNullOrWhiteSpace(info.Platform) ? "unknown" : info.Platform;
        Address = info.Address;
        TlsPort = info.TlsPort;
        Fingerprint = record?.Fingerprint ?? info.Fingerprint;
        var outgoingToken = record == null ? null : PulseSend.Core.Services.TrustedDeviceRegistry.ResolveOutgoingToken(record);
        var incomingToken = record == null ? null : PulseSend.Core.Services.TrustedDeviceRegistry.ResolveIncomingToken(record);
        CanSend = !string.IsNullOrWhiteSpace(outgoingToken);
        CanReceive = !string.IsNullOrWhiteSpace(incomingToken);
        LastSeen = info.LastSeen;
        RaisePropertyChanged(nameof(PairLabel));
    }

    public DeviceInfo ToDeviceInfo() => new()
    {
        Id = Id,
        Name = Name,
        Platform = Platform,
        Address = Address,
        TlsPort = TlsPort,
        Fingerprint = Fingerprint,
        IsTrusted = CanSend,
        LastSeen = LastSeen
    };

    public void RefreshOnlineStatus()
    {
        UpdateOnlineStatus();
    }

    public void ClearPairState()
    {
        _canSend = false;
        _canReceive = false;
        RaisePropertyChanged(nameof(TrustLabel));
        RaisePropertyChanged(nameof(StatusLabel));
        RaisePropertyChanged(nameof(PairLabel));
    }

    public void Touch()
    {
        LastSeen = DateTime.Now;
    }

    public void MarkOffline()
    {
        LastSeen = DateTime.MinValue;
    }

    private void UpdateOnlineStatus()
    {
        var onlineWindow = TimeSpan.FromSeconds(3);
        IsOnline = DateTime.Now - LastSeen <= onlineWindow;
    }
}






