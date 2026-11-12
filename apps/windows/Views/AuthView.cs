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
    private readonly Grid _modeGrid;
    private readonly ColumnDefinition _registerCol;
    private readonly ColumnDefinition _resetCol;
    private readonly Button _modeLoginBtn;
    private readonly Button _modeRegisterBtn;
    private readonly Button _modeResetBtn;
    private readonly Border _formCard;
    private readonly TextBox _emailBox;
    private readonly PasswordBox _passwordBox;
    private readonly TextBox _mfaCodeBox;
    private readonly Grid _emailCodeRow;
    private readonly TextBox _emailCodeBox;
    private readonly Button _sendCodeBtn;
    private readonly TextBox _inviteBox;
    private readonly StackPanel _resetConfirmPanel;
    private readonly Button _sendResetBtn;
    private readonly TextBox _resetTokenBox;
    private readonly PasswordBox _resetNewPasswordBox;
    private readonly Button _submitBtn;
    private readonly StackPanel _browserPanel;
    private readonly Border _messageCard;
    private readonly TextBlock _messageLabel;
    private string _mode = "login";
    private string? _localInfoMessage;
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

        _modeGrid = new Grid { ColumnSpacing = 8 };
        _modeGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        _registerCol = new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) };
        _resetCol = new ColumnDefinition { Width = new GridLength(0) };
        _modeGrid.ColumnDefinitions.Add(_registerCol);
        _modeGrid.ColumnDefinitions.Add(_resetCol);
        _modeLoginBtn = new Button { Content = "登录", HorizontalAlignment = HorizontalAlignment.Stretch };
        _modeRegisterBtn = new Button { Content = "注册", HorizontalAlignment = HorizontalAlignment.Stretch };
        _modeResetBtn = new Button { Content = "找回密码", HorizontalAlignment = HorizontalAlignment.Stretch, Visibility = Visibility.Collapsed };
        _modeLoginBtn.Click += (_, _) => SetMode("login");
        _modeRegisterBtn.Click += (_, _) => SetMode("register");
        _modeResetBtn.Click += (_, _) => SetMode("reset");
        Grid.SetColumn(_modeRegisterBtn, 1);
        Grid.SetColumn(_modeResetBtn, 2);
        _modeGrid.Children.Add(_modeLoginBtn);
        _modeGrid.Children.Add(_modeRegisterBtn);
        _modeGrid.Children.Add(_modeResetBtn);
        outer.Children.Add(_modeGrid);

        var formStack = new StackPanel { Spacing = 10 };
        _emailBox = new TextBox { Header = "邮箱", PlaceholderText = "name@example.com" };
        _passwordBox = new PasswordBox { Header = "密码" };
        _mfaCodeBox = new TextBox
        {
            Header = "两步验证码",
            PlaceholderText = "6 位动态验证码",
            MaxLength = 6,
            Visibility = Visibility.Collapsed,
        };

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

        _sendResetBtn = new Button
        {
            Content = "发送重置邮件",
            HorizontalAlignment = HorizontalAlignment.Stretch,
            Visibility = Visibility.Collapsed,
        };
        _sendResetBtn.Click += async (_, _) => await OnSendResetAsync();

        _resetConfirmPanel = new StackPanel { Spacing = 10, Visibility = Visibility.Collapsed };
        _resetTokenBox = new TextBox { Header = "邮件重置码或链接", PlaceholderText = "粘贴邮件中的重置码或完整重置链接" };
        _resetNewPasswordBox = new PasswordBox { Header = "新密码（至少 10 位）" };
        _resetConfirmPanel.Children.Add(_resetTokenBox);
        _resetConfirmPanel.Children.Add(_resetNewPasswordBox);

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
        formStack.Children.Add(_mfaCodeBox);
        formStack.Children.Add(_emailCodeRow);
        formStack.Children.Add(_inviteBox);
        formStack.Children.Add(_sendResetBtn);
        formStack.Children.Add(_resetConfirmPanel);
        formStack.Children.Add(_submitBtn);
        _formCard = NetoUiFactory.CreateCard(formStack);
        outer.Children.Add(_formCard);

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

        SetMode("login");
        Root = new ScrollViewer { Content = outer };
    }

    private void SetMode(string mode)
    {
        _mode = mode;
        _localInfoMessage = null;
        NetoUiFactory.ApplyPrimaryButton(_modeLoginBtn, mode == "login");
        NetoUiFactory.ApplyPrimaryButton(_modeRegisterBtn, mode == "register");
        NetoUiFactory.ApplyPrimaryButton(_modeResetBtn, mode == "reset");
        _submitBtn.Content = mode switch
        {
            "register" => "注册并登录",
            "reset" => "确认重置密码",
            _ => "登录",
        };
        ApplySnapshot(_panel.Snapshot);
    }

    public void ApplySnapshot(PanelStateSnapshot snap)
    {
        if (_mode == "register" && !snap.RegistrationEnabled)
        {
            _mode = "login";
            NetoUiFactory.ApplyPrimaryButton(_modeLoginBtn, true);
            NetoUiFactory.ApplyPrimaryButton(_modeRegisterBtn, false);
            _submitBtn.Content = "登录";
        }
        if (_mode == "reset" && !snap.PasswordResetEnabled)
        {
            _mode = "login";
            NetoUiFactory.ApplyPrimaryButton(_modeLoginBtn, true);
            NetoUiFactory.ApplyPrimaryButton(_modeResetBtn, false);
            _submitBtn.Content = "登录";
        }

        _appTitle.Text = string.IsNullOrWhiteSpace(snap.AppName) ? "XBClient" : snap.AppName;
        _modeGrid.Visibility = (snap.RegistrationEnabled || snap.PasswordResetEnabled)
            ? Visibility.Visible
            : Visibility.Collapsed;
        _modeRegisterBtn.Visibility = snap.RegistrationEnabled ? Visibility.Visible : Visibility.Collapsed;
        _registerCol.Width = snap.RegistrationEnabled ? new GridLength(1, GridUnitType.Star) : new GridLength(0);
        _modeResetBtn.Visibility = snap.PasswordResetEnabled ? Visibility.Visible : Visibility.Collapsed;
        _resetCol.Width = snap.PasswordResetEnabled ? new GridLength(1, GridUnitType.Star) : new GridLength(0);

        var challengeBlocksForm = snap.IsPxBoard && (
            (_mode == "login" && snap.LoginChallengeRequired) ||
            (_mode == "register" && snap.RegisterChallengeRequired) ||
            (_mode == "reset" && snap.ResetChallengeRequired)
        );
        _formCard.Visibility = challengeBlocksForm ? Visibility.Collapsed : Visibility.Visible;

        var isLogin = _mode == "login";
        var isRegister = _mode == "register";
        var isReset = _mode == "reset";

        _passwordBox.Visibility = isReset ? Visibility.Collapsed : Visibility.Visible;
        _passwordBox.Header = isRegister && snap.IsPxBoard ? "密码（至少 10 位）" : "密码";
        _mfaCodeBox.Visibility = isLogin && snap.MfaRequired ? Visibility.Visible : Visibility.Collapsed;

        var needCode = isRegister && snap.EmailVerifyRequired && !snap.IsPxBoard;
        _emailCodeRow.Visibility = needCode ? Visibility.Visible : Visibility.Collapsed;
        _inviteBox.Header = snap.IsPxBoard ? "推广码（选填）" : "邀请码（选填）";
        _inviteBox.Visibility = isRegister ? Visibility.Visible : Visibility.Collapsed;
        _sendResetBtn.Visibility = isReset ? Visibility.Visible : Visibility.Collapsed;
        _resetConfirmPanel.Visibility = isReset ? Visibility.Visible : Visibility.Collapsed;

        _submitBtn.IsEnabled = !_panel.Busy && !snap.BrowserLoginPending;
        _sendCodeBtn.IsEnabled = !_panel.Busy;
        _sendResetBtn.IsEnabled = !_panel.Busy;

        var sig = $"{_mode}|{snap.IsPxBoard}|{snap.LoginChallengeRequired}|{snap.RegisterChallengeRequired}|{snap.ResetChallengeRequired}|{snap.BrowserLoginPending}|{_panel.Busy}|{string.Join(",", snap.OAuthProviders.Select(p => p.Id + ":" + p.Label))}";
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
        else if (!string.IsNullOrWhiteSpace(_localInfoMessage))
        {
            _messageLabel.Foreground = new SolidColorBrush(Color.FromArgb(255, 46, 184, 128));
            _messageLabel.Text = _localInfoMessage;
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

        if (_mode == "reset")
        {
            if (snap.IsPxBoard && snap.ResetChallengeRequired && !string.IsNullOrEmpty(snap.BaseUrl))
            {
                var resetWebBtn = new Button
                {
                    Content = "在浏览器中找回密码",
                    HorizontalAlignment = HorizontalAlignment.Stretch,
                    IsEnabled = !_panel.Busy,
                };
                NetoUiFactory.ApplyPrimaryButton(resetWebBtn, true);
                resetWebBtn.Click += async (_, _) => await _panel.OpenUrlAsync($"{snap.BaseUrl}/forgot");
                _browserPanel.Children.Add(resetWebBtn);
            }
            return;
        }

        if (snap.IsPxBoard)
        {
            var challengePrimary = (_mode == "login" && snap.LoginChallengeRequired)
                || (_mode == "register" && snap.RegisterChallengeRequired);
            var webBtn = new Button
            {
                Content = _mode == "register" && snap.RegisterChallengeRequired
                    ? "在浏览器中完成注册与登录"
                    : challengePrimary ? "浏览器登录" : "浏览器授权登录",
                HorizontalAlignment = HorizontalAlignment.Stretch,
                IsEnabled = !_panel.Busy,
            };
            if (challengePrimary) NetoUiFactory.ApplyPrimaryButton(webBtn, true);
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
            _localInfoMessage = "验证码已发送，请查收邮箱。";
            ApplySnapshot(_panel.Snapshot);
        }
        catch
        {
        }
    }

    private async Task OnSendResetAsync()
    {
        var email = _emailBox.Text.Trim();
        if (string.IsNullOrEmpty(email)) return;
        try
        {
            _localInfoMessage = await _panel.RequestPasswordResetAsync(email);
            ApplySnapshot(_panel.Snapshot);
        }
        catch
        {
        }
    }

    private async Task OnSubmitAsync()
    {
        if (_mode == "reset")
        {
            var token = _resetTokenBox.Text.Trim();
            var newPw = _resetNewPasswordBox.Password;
            if (string.IsNullOrEmpty(token) || newPw.Length < 10) return;
            try
            {
                await _panel.ConfirmPasswordResetAsync(token, newPw);
                _resetTokenBox.Text = "";
                _resetNewPasswordBox.Password = "";
                SetMode("login");
                _localInfoMessage = "密码已重置，请使用新密码登录。";
                ApplySnapshot(_panel.Snapshot);
            }
            catch
            {
            }
            return;
        }

        var email = _emailBox.Text.Trim();
        var pw = _passwordBox.Password;
        if (string.IsNullOrEmpty(email) || string.IsNullOrEmpty(pw)) return;
        if (_mode == "register")
        {
            await _panel.RegisterAsync(email, pw, _inviteBox.Text.Trim(), _emailCodeBox.Text.Trim());
        }
        else
        {
            var code = _mfaCodeBox.Text.Trim();
            await _panel.LoginPasswordAsync(email, pw, string.IsNullOrEmpty(code) ? null : code);
        }
    }
}
