using Microsoft.UI;
using Microsoft.UI.Text;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;
using Neto.Sdk.Windows.Views;
using Windows.UI;

namespace XBClient.Windows.Views;

public sealed class AuthView
{
    public ScrollViewer Root { get; }
    private readonly XbPanelService _panel;
    private readonly TextBlock _appTitle;
    private readonly Button _modeLoginBtn;
    private readonly Button _modeRegisterBtn;
    private readonly TextBox _emailBox;
    private readonly PasswordBox _passwordBox;
    private readonly Grid _emailCodeRow;
    private readonly TextBox _emailCodeBox;
    private readonly Button _sendCodeBtn;
    private readonly TextBox _inviteBox;
    private readonly Button _submitBtn;
    private readonly StackPanel _browserPanel;
    private readonly Border _messageCard;
    private readonly TextBlock _messageLabel;
    private bool _registerMode;
    private string _providerSig = "";

    public AuthView(XbPanelService panel)
    {
        _panel = panel;

        var outer = new StackPanel
        {
            MaxWidth = 400,
            HorizontalAlignment = HorizontalAlignment.Stretch,
            VerticalAlignment = VerticalAlignment.Center,
            Spacing = 12,
            Padding = new Thickness(20, 28, 20, 28),
        };

        _appTitle = new TextBlock
        {
            Text = panel.Snapshot.AppName,
            FontSize = 20,
            FontWeight = FontWeights.SemiBold,
            HorizontalAlignment = HorizontalAlignment.Center,
        };
        outer.Children.Add(_appTitle);

        var modeGrid = new Grid { ColumnSpacing = 8 };
        modeGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        modeGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        _modeLoginBtn = new Button { Content = "登录", HorizontalAlignment = HorizontalAlignment.Stretch };
        _modeRegisterBtn = new Button { Content = "注册", HorizontalAlignment = HorizontalAlignment.Stretch };
        _modeLoginBtn.Click += (_, _) => SetMode(false);
        _modeRegisterBtn.Click += (_, _) => SetMode(true);
        Grid.SetColumn(_modeRegisterBtn, 1);
        modeGrid.Children.Add(_modeLoginBtn);
        modeGrid.Children.Add(_modeRegisterBtn);
        outer.Children.Add(modeGrid);

        var formStack = new StackPanel { Spacing = 10 };
        _emailBox = new TextBox { Header = "邮箱", PlaceholderText = "name@example.com" };
        _passwordBox = new PasswordBox { Header = "密码" };

        _emailCodeRow = new Grid { ColumnSpacing = 8, Visibility = Visibility.Collapsed };
        _emailCodeRow.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        _emailCodeRow.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        _emailCodeBox = new TextBox { Header = "邮箱验证码" };
        _sendCodeBtn = new Button { Content = "发送验证码", VerticalAlignment = VerticalAlignment.Bottom };
        _sendCodeBtn.Click += async (_, _) => await OnSendCodeAsync();
        Grid.SetColumn(_sendCodeBtn, 1);
        _emailCodeRow.Children.Add(_emailCodeBox);
        _emailCodeRow.Children.Add(_sendCodeBtn);

        _inviteBox = new TextBox { Header = "邀请码（选填）", Visibility = Visibility.Collapsed };

        _submitBtn = new Button
        {
            Content = "登录",
            Height = 38,
            HorizontalAlignment = HorizontalAlignment.Stretch,
        };
        NetoUiFactory.ApplyPrimaryButton(_submitBtn, true);
        _submitBtn.Click += async (_, _) => await OnSubmitAsync();

        formStack.Children.Add(_emailBox);
        formStack.Children.Add(_passwordBox);
        formStack.Children.Add(_emailCodeRow);
        formStack.Children.Add(_inviteBox);
        formStack.Children.Add(_submitBtn);
        outer.Children.Add(NetoUiFactory.CreateCard(formStack));

        _browserPanel = new StackPanel { Spacing = 8 };
        outer.Children.Add(_browserPanel);

        _messageLabel = new TextBlock
        {
            FontSize = 12,
            TextWrapping = TextWrapping.Wrap,
            Foreground = new SolidColorBrush(Color.FromArgb(255, 216, 82, 76)),
        };
        _messageCard = NetoUiFactory.CreateCard(_messageLabel, new Thickness(12, 8, 12, 8));
        _messageCard.Visibility = Visibility.Collapsed;
        outer.Children.Add(_messageCard);

        SetMode(false);
        Root = new ScrollViewer { Content = outer };
    }

    private void SetMode(bool register)
    {
        _registerMode = register;
        NetoUiFactory.ApplyPrimaryButton(_modeLoginBtn, !register);
        NetoUiFactory.ApplyPrimaryButton(_modeRegisterBtn, register);
        _submitBtn.Content = register ? "注册并登录" : "登录";
        ApplySnapshot(_panel.Snapshot);
    }

    public void ApplySnapshot(PanelStateSnapshot snap)
    {
        _appTitle.Text = string.IsNullOrWhiteSpace(snap.AppName) ? "XBClient" : snap.AppName;
        var needCode = _registerMode && (snap.EmailVerifyRequired || snap.IsPxBoard);
        _emailCodeRow.Visibility = needCode ? Visibility.Visible : Visibility.Collapsed;
        _inviteBox.Visibility = _registerMode ? Visibility.Visible : Visibility.Collapsed;
        _submitBtn.IsEnabled = !_panel.Busy && !snap.BrowserLoginPending;
        _sendCodeBtn.IsEnabled = !_panel.Busy;

        var sig = $"{snap.IsPxBoard}|{snap.BrowserLoginPending}|{_panel.Busy}|{string.Join(",", snap.OAuthProviders.Select(p => p.Id + ":" + p.Label))}";
        if (_providerSig != sig)
        {
            _providerSig = sig;
            RebuildBrowserButtons(snap);
        }

        var err = _panel.LastError ?? snap.LastError;
        if (!string.IsNullOrWhiteSpace(err))
        {
            _messageLabel.Foreground = new SolidColorBrush(Color.FromArgb(255, 216, 82, 76));
            _messageLabel.Text = err;
            _messageCard.Visibility = Visibility.Visible;
        }
        else if (snap.BrowserLoginPending)
        {
            _messageLabel.Foreground = NetoUiFactory.BrushResource("TextFillColorSecondaryBrush", Colors.Gray);
            _messageLabel.Text = "请在已打开的系统浏览器中完成确认。";
            _messageCard.Visibility = Visibility.Visible;
        }
        else
        {
            _messageCard.Visibility = Visibility.Collapsed;
        }
    }

    private void RebuildBrowserButtons(PanelStateSnapshot snap)
    {
        _browserPanel.Children.Clear();
        if (snap.BrowserLoginPending)
        {
            var cancelBtn = new Button
            {
                Content = "取消浏览器授权",
                HorizontalAlignment = HorizontalAlignment.Stretch,
            };
            cancelBtn.Click += async (_, _) => await _panel.CancelBrowserLoginAsync();
            _browserPanel.Children.Add(cancelBtn);
            return;
        }

        if (snap.IsPxBoard)
        {
            var webBtn = new Button
            {
                Content = "浏览器授权登录",
                HorizontalAlignment = HorizontalAlignment.Stretch,
                IsEnabled = !_panel.Busy,
            };
            webBtn.Click += async (_, _) => await _panel.BrowserLoginAsync(null);
            _browserPanel.Children.Add(webBtn);
        }

        foreach (var provider in snap.OAuthProviders)
        {
            var pid = provider.Id;
            var btn = new Button
            {
                Content = $"{provider.Label} 登录",
                HorizontalAlignment = HorizontalAlignment.Stretch,
                IsEnabled = !_panel.Busy,
            };
            btn.Click += async (_, _) => await _panel.BrowserLoginAsync(pid);
            _browserPanel.Children.Add(btn);
        }
    }

    private async Task OnSendCodeAsync()
    {
        var email = _emailBox.Text.Trim();
        if (string.IsNullOrEmpty(email)) return;
        try
        {
            await _panel.SendEmailCodeAsync(email);
            _messageLabel.Foreground = new SolidColorBrush(Color.FromArgb(255, 46, 184, 128));
            _messageLabel.Text = "验证码已发送，请查收邮箱。";
            _messageCard.Visibility = Visibility.Visible;
        }
        catch
        {
        }
    }

    private async Task OnSubmitAsync()
    {
        var email = _emailBox.Text.Trim();
        var pw = _passwordBox.Password;
        if (string.IsNullOrEmpty(email) || string.IsNullOrEmpty(pw)) return;
        if (_registerMode)
        {
            await _panel.RegisterAsync(email, pw, _inviteBox.Text.Trim(), _emailCodeBox.Text.Trim());
        }
        else
        {
            await _panel.LoginPasswordAsync(email, pw);
        }
    }
}
