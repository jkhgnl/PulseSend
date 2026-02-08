using Avalonia;
using Avalonia.Controls;
using Avalonia.Controls.ApplicationLifetimes;
using Avalonia.Controls.Platform;
using Avalonia.Markup.Xaml;
using Avalonia.Platform;
using PulseSend.Windows.Views;

namespace PulseSend.Windows;

public partial class App : Application
{
    private TrayIcon? _tray;
    private Window? _mainWindow;

    public override void Initialize()
    {
        AvaloniaXamlLoader.Load(this);
    }

    public override void OnFrameworkInitializationCompleted()
    {
        if (ApplicationLifetime is IClassicDesktopStyleApplicationLifetime desktop)
        {
            var mainWindow = new MainWindow();
            _mainWindow = mainWindow;
            desktop.MainWindow = mainWindow;

            _tray = BuildTray();
            desktop.Exit += (_, _) => _tray?.Dispose();
        }

        base.OnFrameworkInitializationCompleted();
    }

    private TrayIcon BuildTray()
    {
        var tray = new TrayIcon
        {
            ToolTipText = "脉冲传输",
            Icon = LoadIcon()
        };

        var menu = new NativeMenu();
        var showItem = new NativeMenuItem("显示主窗口");
        showItem.Click += (_, _) => ShowMainWindow();
        var exitItem = new NativeMenuItem("退出");
        exitItem.Click += (_, _) => Shutdown();
        menu.Items.Add(showItem);
        menu.Items.Add(new NativeMenuItemSeparator());
        menu.Items.Add(exitItem);
        tray.Menu = menu;
        tray.Clicked += (_, _) => ShowMainWindow();
        return tray;
    }

    private WindowIcon? LoadIcon()
    {
        try
        {
            return new WindowIcon(AssetLoader.Open(new Uri("avares://PulseSend.Windows/Assets/icon.ico")));
        }
        catch
        {
            return null;
        }
    }

    private void ShowMainWindow()
    {
        if (_mainWindow == null)
        {
            return;
        }
        _mainWindow.Show();
        _mainWindow.WindowState = WindowState.Normal;
        _mainWindow.Activate();
    }

    private void Shutdown()
    {
        if (_mainWindow is MainWindow window)
        {
            window.AllowClose = true;
        }
        if (ApplicationLifetime is IClassicDesktopStyleApplicationLifetime desktop)
        {
            desktop.Shutdown();
        }
    }
}






