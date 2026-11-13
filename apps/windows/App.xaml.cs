using Microsoft.UI.Xaml;

namespace XBClient.Windows;

public partial class App : Application
{
    private MainWindow? _window;

    public App()
    {
        Environment.CurrentDirectory = AppContext.BaseDirectory;
        InitializeComponent();
    }

    protected override void OnLaunched(LaunchActivatedEventArgs args)
    {
        var minimized = Environment.GetCommandLineArgs()
            .Any(a => a.Equals("--minimized", StringComparison.OrdinalIgnoreCase));
        _window = new MainWindow(minimized);
        if (!minimized)
        {
            _window.Activate();
        }
    }
}
