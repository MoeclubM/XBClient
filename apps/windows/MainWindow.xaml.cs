using Microsoft.UI;
using Microsoft.UI.Text;
using Microsoft.UI.Windowing;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Neto.Sdk.Windows;
using Neto.Sdk.Windows.Views;
using Windows.Graphics;
using WinRT.Interop;
using XBClient.Windows.Views;

namespace XBClient.Windows;

public sealed partial class MainWindow : Window
{
    private readonly NetoClient _netoClient;
    private readonly XbPanelService _panelService;
    private readonly SingleInstanceGuard? _instanceGuard;
    private readonly IntPtr _hwnd;
    private readonly AuthView _authView;
    private readonly ConnectPageView _connectView;
    private readonly DashboardPageView _dashboardView;
    private readonly NodesPageView _nodesView;
    private readonly ConnectionsPageView _connectionsView;
    private readonly PlansPageView _plansView;
    private readonly AccountPageView _accountView;
    private readonly SettingsPageView _settingsView;
    private readonly AppWindow _appWindow;
    private readonly Win32TrayIcon _tray;
    private string _currentTab = "connect";
    private string _lastBlockReason = "__unset__";
    private string _appliedTheme = "";
    private bool _syncingNav;
    private bool _quitting;

    public bool StartedHidden { get; }

    public MainWindow(bool startMinimized, bool isRelaunchElevated, SingleInstanceGuard? instanceGuard)
    {
        _instanceGuard = instanceGuard;
        InitializeComponent();

        _hwnd = WindowNative.GetWindowHandle(this);
        var windowId = Win32Interop.GetWindowIdFromWindow(_hwnd);
        _appWindow = AppWindow.GetFromWindowId(windowId);
        var iconPath = Path.Combine(AppContext.BaseDirectory, "Assets", "app.ico");
        if (File.Exists(iconPath))
        {
            _appWindow.SetIcon(iconPath);
        }
        NetoWindowHelper.ConfigureWindow(_appWindow, 940, 660);
        _appWindow.Closing += OnAppWindowClosing;

        var dataDir = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "XBClient");
        Directory.CreateDirectory(dataDir);

        NetoNativeBridge.UseNativeLibrary("xbclient_ffi");
        var initialPanel = XbPanelBridge.Init(dataDir);
        var appTitle = string.IsNullOrWhiteSpace(initialPanel.AppName) ? "XBClient" : initialPanel.AppName;
        Title = appTitle;

        _netoClient = new NetoClient(dataDir, "XBClient", "Aerion", DispatcherQueue);
        _panelService = new XbPanelService(initialPanel, DispatcherQueue, () => _netoClient.RefreshSnapshotAsync());

        _authView = new AuthView(_panelService);
        AuthHost.Children.Add(_authView.Root);

        _connectView = new ConnectPageView(_netoClient, () => SwitchTab("nodes"), () => SwitchTab("account"), RequestElevation);
        _dashboardView = new DashboardPageView(
            _netoClient,
            () => SwitchTab("nodes"),
            () => SwitchTab("account"),
            RequestElevation,
            detachHeaderActions: true,
            onOpenConnections: () => SwitchTab("connections"));
        _nodesView = new NodesPageView(_netoClient);
        _connectionsView = new ConnectionsPageView(_netoClient, detachHeaderActions: true);
        _plansView = new PlansPageView(_panelService);
        _accountView = new AccountPageView(_panelService);
        _settingsView = new SettingsPageView(_netoClient, ApplyThemeMode, RequestElevation);
        AppendCommercialLinks(initialPanel);
        NavSettingsItem.Tapped += (_, _) => _settingsView.ResetToRoot();

        ContentHost.Children.Add(_connectView.Root);
        ContentHost.Children.Add(_dashboardView.Root);
        ContentHost.Children.Add(_nodesView.Root);
        ContentHost.Children.Add(_connectionsView.Root);
        ContentHost.Children.Add(_plansView.Root);
        ContentHost.Children.Add(_accountView.Root);
        ContentHost.Children.Add(_settingsView.Root);

        _tray = new Win32TrayIcon(_hwnd, appTitle);
        _tray.ShowRequested += ShowFromExternalActivation;
        _tray.PrimaryClickRequested += () => DispatcherQueue.TryEnqueue(() =>
        {
            if (NetoWindowHelper.IsWindowForegroundAndVisible(_appWindow, _hwnd))
            {
                _appWindow.Hide();
            }
            else
            {
                NetoWindowHelper.RestoreAndActivate(this, _appWindow, _hwnd);
            }
        });
        _tray.ToggleConnectRequested += () => DispatcherQueue.TryEnqueue(async () =>
        {
            if (_panelService.Snapshot.LoggedIn && string.IsNullOrEmpty(_panelService.Snapshot.BlockReason))
            {
                await _netoClient.ToggleConnectAsync();
            }
            else
            {
                NetoWindowHelper.RestoreAndActivate(this, _appWindow, _hwnd);
            }
        });
        _tray.SelectRoutingModeRequested += mode => DispatcherQueue.TryEnqueue(async () => await _netoClient.SetRoutingModeAsync(mode));
        _tray.SelectNodeRequested += idx => DispatcherQueue.TryEnqueue(async () => await _netoClient.SelectNodeAsync(idx));
        _tray.ToggleSystemProxyRequested += () => DispatcherQueue.TryEnqueue(async () => await _netoClient.SetSystemProxyAsync(!_netoClient.Snapshot.Settings.SystemProxyEnabled));
        _tray.ToggleTunRequested += () => DispatcherQueue.TryEnqueue(async () =>
        {
            if (!_netoClient.Snapshot.Settings.TunEnabled && !_netoClient.Snapshot.Connection.CanUseTun)
            {
                RequestElevation();
                return;
            }
            await _netoClient.SetTunEnabledAsync(!_netoClient.Snapshot.Settings.TunEnabled);
        });
        _tray.CopyProxyCommandRequested += () => DispatcherQueue.TryEnqueue(() =>
        {
            NetoWindowHelper.SetClipboardText(NetoWindowHelper.BuildTerminalProxyCommand(_netoClient.Snapshot));
        });
        _tray.CloseAllConnectionsRequested += () => DispatcherQueue.TryEnqueue(async () => await _netoClient.CloseAllConnectionsAsync());
        _tray.RestartElevatedRequested += () => DispatcherQueue.TryEnqueue(RequestElevation);
        _tray.SessionEnding += () => NetoNativeBridge.Shutdown();
        _tray.ExitRequested += () => DispatcherQueue.TryEnqueue(ExitApplication);

        _netoClient.SnapshotChanged += ApplyNetoSnapshot;
        _panelService.SnapshotChanged += ApplyPanelSnapshot;

        var initialTab = Environment.GetCommandLineArgs()
            .FirstOrDefault(a => a.StartsWith("--tab=", StringComparison.OrdinalIgnoreCase))?[6..]?.ToLowerInvariant() ?? "connect";
        SwitchTab(initialTab);
        ApplyPanelSnapshot(initialPanel);
        ApplyNetoSnapshot(_netoClient.Snapshot);
        _ = _panelService.InitOrSyncAsync();

        StartedHidden = (startMinimized || _netoClient.Snapshot.Settings.SilentStart) && !isRelaunchElevated;
        if (StartedHidden)
        {
            _appWindow.Hide();
        }
    }

    public void ShowFromExternalActivation()
    {
        DispatcherQueue.TryEnqueue(() => NetoWindowHelper.RestoreAndActivate(this, _appWindow, _hwnd));
    }

    private void ApplyThemeMode(string themeMode)
    {
        if (_appliedTheme == themeMode) return;
        _appliedTheme = themeMode;
        NetoWindowHelper.ApplyTheme(RootGrid, _hwnd, themeMode);
    }

    private void RequestElevation()
    {
        if (NetoWindowHelper.TryRestartElevated(_instanceGuard))
        {
            ExitApplication();
        }
    }

    private void AppendCommercialLinks(PanelStateSnapshot snap)
    {
        var links = new List<(string Label, string Url)>
        {
            ("官方网站", snap.WebsiteUrl),
            ("服务协议", snap.UserAgreementUrl),
            ("隐私政策", snap.PrivacyPolicyUrl),
        }.Where(x => !string.IsNullOrWhiteSpace(x.Url)).ToList();
        if (links.Count == 0)
        {
            _settingsView.SetExtraFooter(null);
            return;
        }
        var section = new StackPanel { Spacing = 8 };
        section.Children.Add(NetoUiFactory.CreateInlineCardHeader("站点与条款"));
        var row = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 8 };
        foreach (var (label, url) in links)
        {
            var btn = new Button { Content = label };
            var target = url;
            btn.Click += async (_, _) => await _panelService.OpenUrlAsync(target);
            row.Children.Add(btn);
        }
        section.Children.Add(row);
        _settingsView.SetExtraFooter(NetoUiFactory.CreateCard(section));
    }

    private void OnNavSelectionChanged(SelectorBar sender, SelectorBarSelectionChangedEventArgs args)
    {
        if (_syncingNav) return;
        if (sender.SelectedItem is SelectorBarItem { Tag: string tag })
        {
            SwitchTab(tag);
        }
    }

    private void SwitchTab(string tab)
    {
        if (tab == "plans" && !_panelService.Snapshot.ShowPlansTab)
        {
            tab = "account";
        }
        _currentTab = tab;
        _syncingNav = true;
        BottomSelectorBar.SelectedItem = tab switch
        {
            "dashboard" => NavDashboardItem,
            "nodes" => NavNodesItem,
            "connections" => NavConnectionsItem,
            "plans" => NavPlansItem,
            "account" => NavAccountItem,
            "settings" => NavSettingsItem,
            _ => NavConnectItem,
        };
        _syncingNav = false;
        if (tab == "settings") _settingsView.ResetToRoot();

        if (tab == "connect")
        {
            TopHeaderBorder.Visibility = Visibility.Collapsed;
            PageHeaderActionHost.Content = null;
        }
        else
        {
            TopHeaderBorder.Visibility = Visibility.Visible;
            PageHeaderText.Text = tab switch
            {
                "dashboard" => "主页",
                "nodes" => "节点",
                "connections" => "会话",
                "plans" => "套餐",
                "account" => "我的",
                "settings" => "设置",
                _ => "",
            };
            PageHeaderActionHost.Content = tab switch
            {
                "dashboard" => _dashboardView.HeaderActions,
                "connections" => _connectionsView.HeaderActions,
                _ => null,
            };
        }

        _connectView.Root.Visibility = tab == "connect" ? Visibility.Visible : Visibility.Collapsed;
        _dashboardView.Root.Visibility = tab == "dashboard" ? Visibility.Visible : Visibility.Collapsed;
        _nodesView.Root.Visibility = tab == "nodes" ? Visibility.Visible : Visibility.Collapsed;
        _connectionsView.Root.Visibility = tab == "connections" ? Visibility.Visible : Visibility.Collapsed;
        _plansView.Root.Visibility = tab == "plans" ? Visibility.Visible : Visibility.Collapsed;
        _accountView.Root.Visibility = tab == "account" ? Visibility.Visible : Visibility.Collapsed;
        _settingsView.Root.Visibility = tab == "settings" ? Visibility.Visible : Visibility.Collapsed;
        if (tab == "connections")
        {
            _connectionsView.ApplySnapshot(_netoClient.Snapshot);
        }
    }

    private void ApplyPanelSnapshot(PanelStateSnapshot snap)
    {
        AuthHost.Visibility = snap.LoggedIn ? Visibility.Collapsed : Visibility.Visible;
        MainShellGrid.Visibility = snap.LoggedIn ? Visibility.Visible : Visibility.Collapsed;
        _authView.ApplySnapshot(snap);
        SyncTray();
        if (!snap.LoggedIn) return;

        NavPlansItem.Visibility = snap.ShowPlansTab ? Visibility.Visible : Visibility.Collapsed;
        if (_currentTab == "plans" && !snap.ShowPlansTab) SwitchTab("account");

        if (_lastBlockReason != snap.BlockReason)
        {
            _lastBlockReason = snap.BlockReason;
            _connectView.SetBlockedElement(string.IsNullOrEmpty(snap.BlockReason) ? null : BuildBlockedCard(snap));
        }
        _dashboardView.SetExtraHeader(BuildAccountHeaderCard(snap));
        _plansView.ApplySnapshot(snap);
        _accountView.ApplySnapshot(snap);
    }

    private UIElement BuildBlockedCard(PanelStateSnapshot snap)
    {
        var (title, body) = XbFormat.DescribeBlockReason(snap.BlockReason);
        var stack = new StackPanel { Spacing = 8 };
        stack.Children.Add(new TextBlock { Text = title, FontSize = 16, FontWeight = FontWeights.SemiBold });
        stack.Children.Add(new TextBlock
        {
            Text = body,
            TextWrapping = TextWrapping.Wrap,
            Foreground = NetoUiFactory.BrushResource("TextFillColorSecondaryBrush", Colors.Gray),
        });
        if (!string.IsNullOrEmpty(snap.SubscriptionSummary))
        {
            stack.Children.Add(new TextBlock { Text = snap.SubscriptionSummary, FontSize = 12 });
        }
        var btn = new Button
        {
            Content = snap.ShowPlansTab ? "开通套餐" : "账户与兑换",
            HorizontalAlignment = HorizontalAlignment.Stretch,
        };
        NetoUiFactory.ApplyPrimaryButton(btn, true);
        btn.Click += (_, _) => SwitchTab(snap.ShowPlansTab ? "plans" : "account");
        stack.Children.Add(btn);
        return NetoUiFactory.CreateCard(stack);
    }

    private UIElement BuildAccountHeaderCard(PanelStateSnapshot snap)
    {
        var grid = new Grid { ColumnSpacing = 10 };
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        var left = new StackPanel { Spacing = 2 };
        left.Children.Add(new TextBlock
        {
            Text = string.IsNullOrEmpty(snap.Email) ? snap.AppName : snap.Email,
            FontWeight = FontWeights.SemiBold,
            TextTrimming = TextTrimming.CharacterEllipsis,
        });
        var parts = new List<string>();
        if (!string.IsNullOrEmpty(snap.SubscriptionSummary)) parts.Add(snap.SubscriptionSummary);
        parts.Add($"余额 {XbFormat.Money(snap.Balance, snap.CurrencySymbol, snap.CurrencyUnit)}");
        left.Children.Add(new TextBlock
        {
            Text = string.Join(" · ", parts),
            FontSize = 12,
            TextTrimming = TextTrimming.CharacterEllipsis,
            Foreground = NetoUiFactory.BrushResource("TextFillColorSecondaryBrush", Colors.Gray),
        });
        var jumpBtn = new Button
        {
            Content = snap.ShowPlansTab ? "套餐" : "账户",
            VerticalAlignment = VerticalAlignment.Center,
        };
        jumpBtn.Click += (_, _) => SwitchTab(snap.ShowPlansTab ? "plans" : "account");
        Grid.SetColumn(jumpBtn, 1);
        grid.Children.Add(left);
        grid.Children.Add(jumpBtn);
        return NetoUiFactory.CreateCard(grid);
    }

    private void ApplyNetoSnapshot(NetoSnapshot snap)
    {
        ApplyThemeMode(snap.Settings.ThemeMode);
        _connectView.ApplySnapshot(snap, _netoClient.LastActionError);
        _dashboardView.ApplySnapshot(snap);
        _nodesView.ApplySnapshot(snap);
        _connectionsView.ApplySnapshot(snap);
        _settingsView.ApplySnapshot(snap);

        var portText = string.IsNullOrEmpty(snap.Connection.HttpProxyAddr) ? $"127.0.0.1:{snap.Settings.MixedPort}" : snap.Connection.HttpProxyAddr;
        BottomStatusText.Text = snap.Connection.CanUseTun ? $"管理员 · {portText}" : portText;
        SyncTray();
    }

    private void SyncTray()
    {
        var panel = _panelService.Snapshot;
        var appTitle = string.IsNullOrWhiteSpace(panel.AppName) ? "XBClient" : panel.AppName;
        var allowConnect = panel.LoggedIn && string.IsNullOrEmpty(panel.BlockReason);
        _tray.SyncFromSnapshot(_netoClient.Snapshot, appTitle, allowConnect);
    }

    private void OnAppWindowClosing(AppWindow sender, AppWindowClosingEventArgs args)
    {
        if (!_quitting)
        {
            args.Cancel = true;
            _appWindow.Hide();
        }
    }

    private void ExitApplication()
    {
        _quitting = true;
        _tray.Dispose();
        _netoClient.Dispose();
        _instanceGuard?.Dispose();
        Close();
    }
}
