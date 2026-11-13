using Microsoft.UI.Xaml;
using Neto.Sdk.Windows;

namespace XBClient.Windows;

public partial class App : Application
{
    private MainWindow? _window;
    private SingleInstanceGuard? _instanceGuard;

    public App()
    {
        Environment.CurrentDirectory = AppContext.BaseDirectory;
        _instanceGuard = SingleInstanceGuard.TryAcquireOrWakeExisting("XBClient", () =>
        {
            _window?.ShowFromExternalActivation();
        });
        if (_instanceGuard == null)
        {
            Environment.Exit(0);
            return;
        }
        InitializeComponent();
    }

    protected override void OnLaunched(LaunchActivatedEventArgs args)
    {
        var cmdArgs = Environment.GetCommandLineArgs();
        var minimized = cmdArgs.Any(a => a.Equals("--minimized", StringComparison.OrdinalIgnoreCase));
        _window = new MainWindow(minimized, _instanceGuard);
        if (!_window.StartedHidden)
        {
            _window.Activate();
        }
    }
}
