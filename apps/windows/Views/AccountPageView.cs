using Microsoft.UI;
using Microsoft.UI.Text;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;
using Neto.Sdk.Windows;
using Neto.Sdk.Windows.Views;
using System.Globalization;
using Windows.UI;

namespace XBClient.Windows.Views;

public sealed class AccountPageView
{
    public ScrollViewer Root { get; }
    private readonly XbPanelService _panel;
    private readonly TextBlock _emailText;
    private readonly TextBlock _quotaText;
    private readonly TextBlock _balanceText;
    private readonly Button _portalBtn;
    private readonly Border _feedbackCard;
    private readonly TextBlock _feedbackText;
    private readonly Border _subLinkCard;
    private readonly StackPanel _subLinkContainer;
    private readonly Border _rechargeCard;
    private readonly TextBox _rechargeAmountBox;
    private readonly ComboBox _rechargeChannelCombo;
    private readonly Border _redeemCard;
    private readonly TextBox _redeemBox;
    private readonly Border _inviteCard;
    private readonly StackPanel _invitePanel;
    private readonly StackPanel _noticesPanel;
    private readonly StackPanel _ticketsSection;
    private readonly TextBox _ticketSubjectBox;
    private readonly TextBox _ticketContentBox;
    private readonly StackPanel _ticketsList;
    private readonly Border _ticketDetailCard;
    private readonly TextBlock _ticketDetailTitle;
    private readonly StackPanel _ticketMessagesPanel;
    private readonly TextBox _ticketReplyBox;
    private readonly Button _ticketReplyBtn;
    private readonly Button _ticketCloseBtn;
    private readonly PasswordBox _oldPasswordBox;
    private readonly TextBox _pwMfaCodeBox;
    private readonly PasswordBox _newPasswordBox;
    private readonly Border _mfaCard;
    private readonly StackPanel _mfaBody;
    private readonly Border _oauthCard;
    private readonly StackPanel _oauthBody;
    private List<(string Id, string Name)> _rechargeChannels = [];
    private MfaSetupInfo? _mfaSetup;
    private string? _selectedTicketId;
    private string _lastSig = "";

    public AccountPageView(XbPanelService panel)
    {
        _panel = panel;

        var outer = new StackPanel
        {
            MaxWidth = 860,
            Spacing = 10,
            Padding = new Thickness(18, 8, 18, 24),
        };

        _feedbackText = new TextBlock { FontSize = 12, TextWrapping = TextWrapping.Wrap };
        _feedbackCard = NetoUiFactory.CreateCard(_feedbackText, new Thickness(12, 8, 12, 8));
        _feedbackCard.Visibility = Visibility.Collapsed;
        outer.Children.Add(_feedbackCard);

        var accGrid = new Grid { ColumnSpacing = 12 };
        accGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        accGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        var accLeft = new StackPanel { Spacing = 4, VerticalAlignment = VerticalAlignment.Center };
        _emailText = new TextBlock { FontSize = 15, FontWeight = FontWeights.SemiBold, TextTrimming = TextTrimming.CharacterEllipsis };
        _quotaText = new TextBlock { FontSize = 12, TextTrimming = TextTrimming.CharacterEllipsis, Foreground = NetoUiFactory.BrushResource("TextFillColorSecondaryBrush", Colors.Gray) };
        _balanceText = new TextBlock { FontSize = 12, TextTrimming = TextTrimming.CharacterEllipsis, Foreground = NetoUiFactory.BrushResource("TextFillColorSecondaryBrush", Colors.Gray) };
        accLeft.Children.Add(_emailText);
        accLeft.Children.Add(_quotaText);
        accLeft.Children.Add(_balanceText);

        var accBtns = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 8, VerticalAlignment = VerticalAlignment.Center };
        var syncBtn = new Button { Content = "同步账户" };
        _portalBtn = new Button { Content = "网页中心" };
        var logoutBtn = new Button { Content = "退出登录" };
        syncBtn.Click += async (_, _) => await _panel.SyncAllAsync();
        _portalBtn.Click += async (_, _) => await _panel.OpenPortalAsync("/user");
        logoutBtn.Click += async (_, _) => await _panel.LogoutAsync();
        accBtns.Children.Add(syncBtn);
        accBtns.Children.Add(_portalBtn);
        accBtns.Children.Add(logoutBtn);
        Grid.SetColumn(accBtns, 1);
        accGrid.Children.Add(accLeft);
        accGrid.Children.Add(accBtns);
        outer.Children.Add(NetoUiFactory.CreateCard(accGrid));

        var subLinkStack = new StackPanel { Spacing = 8 };
        subLinkStack.Children.Add(NetoUiFactory.CreateInlineCardHeader("订阅地址"));
        _subLinkContainer = new StackPanel { Spacing = 6 };
        subLinkStack.Children.Add(_subLinkContainer);
        _subLinkCard = NetoUiFactory.CreateCard(subLinkStack);
        _subLinkCard.Visibility = Visibility.Collapsed;
        outer.Children.Add(_subLinkCard);

        var rechargeStack = new StackPanel { Spacing = 8 };
        rechargeStack.Children.Add(NetoUiFactory.CreateInlineCardHeader("余额充值"));
        var rechargeGrid = new Grid { ColumnSpacing = 8 };
        rechargeGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        rechargeGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        rechargeGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        _rechargeAmountBox = new TextBox { PlaceholderText = "充值金额（如 20）" };
        _rechargeChannelCombo = new ComboBox { HorizontalAlignment = HorizontalAlignment.Stretch };
        var rechargeBtn = new Button { Content = "前往充值" };
        NetoUiFactory.ApplyPrimaryButton(rechargeBtn, true);
        rechargeBtn.Click += async (_, _) =>
        {
            if (!double.TryParse(_rechargeAmountBox.Text.Trim(), NumberStyles.Float, CultureInfo.InvariantCulture, out var amount) || amount < 1.0)
            {
                ShowFeedback("请输入有效的充值金额（最低 1.00）。", error: true);
                return;
            }
            if (_rechargeChannelCombo.SelectedIndex < 0 || _rechargeChannelCombo.SelectedIndex >= _rechargeChannels.Count) return;
            var chId = _rechargeChannels[_rechargeChannelCombo.SelectedIndex].Id;
            rechargeBtn.IsEnabled = false;
            try
            {
                await _panel.RechargeWalletAsync((long)Math.Round(amount * 100.0), chId);
                _rechargeAmountBox.Text = "";
                ShowFeedback("已打开外部收银台，完成支付后请点击同步账户。", error: false);
            }
            catch (Exception ex)
            {
                ShowFeedback(ex.Message, error: true);
            }
            finally
            {
                rechargeBtn.IsEnabled = true;
            }
        };
        Grid.SetColumn(_rechargeChannelCombo, 1);
        Grid.SetColumn(rechargeBtn, 2);
        rechargeGrid.Children.Add(_rechargeAmountBox);
        rechargeGrid.Children.Add(_rechargeChannelCombo);
        rechargeGrid.Children.Add(rechargeBtn);
        rechargeStack.Children.Add(rechargeGrid);
        _rechargeCard = NetoUiFactory.CreateCard(rechargeStack);
        _rechargeCard.Visibility = Visibility.Collapsed;
        outer.Children.Add(_rechargeCard);

        var redeemStack = new StackPanel { Spacing = 8 };
        redeemStack.Children.Add(NetoUiFactory.CreateInlineCardHeader("兑换码"));
        var redeemGrid = new Grid { ColumnSpacing = 8 };
        redeemGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        redeemGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        _redeemBox = new TextBox { PlaceholderText = "输入礼品卡或套餐兑换码" };
        var redeemBtn = new Button { Content = "立即兑换" };
        NetoUiFactory.ApplyPrimaryButton(redeemBtn, true);
        redeemBtn.Click += async (_, _) =>
        {
            var code = _redeemBox.Text.Trim();
            if (string.IsNullOrEmpty(code)) return;
            try
            {
                var msg = await _panel.RedeemCodeAsync(code);
                _redeemBox.Text = "";
                ShowFeedback(msg, error: false);
            }
            catch (Exception ex)
            {
                ShowFeedback(ex.Message, error: true);
            }
        };
        Grid.SetColumn(redeemBtn, 1);
        redeemGrid.Children.Add(_redeemBox);
        redeemGrid.Children.Add(redeemBtn);
        redeemStack.Children.Add(redeemGrid);
        _redeemCard = NetoUiFactory.CreateCard(redeemStack);
        outer.Children.Add(_redeemCard);

        var inviteStack = new StackPanel { Spacing = 8 };
        inviteStack.Children.Add(NetoUiFactory.CreateInlineCardHeader("邀请返利"));
        _invitePanel = new StackPanel { Spacing = 8 };
        inviteStack.Children.Add(_invitePanel);
        _inviteCard = NetoUiFactory.CreateCard(inviteStack);
        outer.Children.Add(_inviteCard);

        _noticesPanel = new StackPanel { Spacing = 8 };
        outer.Children.Add(_noticesPanel);

        _ticketsSection = new StackPanel { Spacing = 8 };
        var newTicketStack = new StackPanel { Spacing = 8 };
        var createTicketBtn = new Button { Content = "提交工单" };
        NetoUiFactory.ApplyPrimaryButton(createTicketBtn, true);
        newTicketStack.Children.Add(NetoUiFactory.CreateInlineCardHeader("工单支持", createTicketBtn));
        _ticketSubjectBox = new TextBox { PlaceholderText = "工单主题" };
        _ticketContentBox = new TextBox
        {
            PlaceholderText = "详细描述遇到的问题",
            AcceptsReturn = true,
            TextWrapping = TextWrapping.Wrap,
            MinHeight = 68,
        };
        createTicketBtn.Click += async (_, _) =>
        {
            var sub = _ticketSubjectBox.Text.Trim();
            var body = _ticketContentBox.Text.Trim();
            if (string.IsNullOrEmpty(sub) || string.IsNullOrEmpty(body)) return;
            await _panel.CreateTicketAsync(sub, body);
            _ticketSubjectBox.Text = "";
            _ticketContentBox.Text = "";
            ShowFeedback("工单已提交。", error: false);
        };
        newTicketStack.Children.Add(_ticketSubjectBox);
        newTicketStack.Children.Add(_ticketContentBox);
        _ticketsSection.Children.Add(NetoUiFactory.CreateCard(newTicketStack));

        _ticketsList = new StackPanel { Spacing = 6 };
        _ticketsSection.Children.Add(_ticketsList);

        var detailStack = new StackPanel { Spacing = 8 };
        _ticketDetailTitle = new TextBlock { FontWeight = FontWeights.SemiBold };
        _ticketMessagesPanel = new StackPanel { Spacing = 6 };
        _ticketReplyBox = new TextBox { PlaceholderText = "输入回复内容" };
        var detailBtns = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 8, HorizontalAlignment = HorizontalAlignment.Right };
        _ticketReplyBtn = new Button { Content = "发送回复" };
        NetoUiFactory.ApplyPrimaryButton(_ticketReplyBtn, true);
        _ticketCloseBtn = new Button { Content = "关闭工单" };
        detailBtns.Children.Add(_ticketCloseBtn);
        detailBtns.Children.Add(_ticketReplyBtn);
        detailStack.Children.Add(_ticketDetailTitle);
        detailStack.Children.Add(_ticketMessagesPanel);
        detailStack.Children.Add(_ticketReplyBox);
        detailStack.Children.Add(detailBtns);
        _ticketDetailCard = NetoUiFactory.CreateCard(detailStack);
        _ticketDetailCard.Visibility = Visibility.Collapsed;
        _ticketReplyBtn.Click += async (_, _) =>
        {
            if (_selectedTicketId == null || string.IsNullOrWhiteSpace(_ticketReplyBox.Text)) return;
            var id = _selectedTicketId;
            await _panel.ReplyTicketAsync(id, _ticketReplyBox.Text.Trim());
            _ticketReplyBox.Text = "";
            await LoadTicketDetailAsync(id);
        };
        _ticketCloseBtn.Click += async (_, _) =>
        {
            if (_selectedTicketId == null) return;
            var id = _selectedTicketId;
            await _panel.CloseTicketAsync(id);
            _ticketDetailCard.Visibility = Visibility.Collapsed;
        };
        _ticketsSection.Children.Add(_ticketDetailCard);
        outer.Children.Add(_ticketsSection);

        var pwStack = new StackPanel { Spacing = 8 };
        var changePwBtn = new Button { Content = "更新密码" };
        pwStack.Children.Add(NetoUiFactory.CreateInlineCardHeader("修改密码", changePwBtn));
        var pwGrid = new Grid { ColumnSpacing = 10 };
        pwGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        pwGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        _oldPasswordBox = new PasswordBox { Header = "当前密码" };
        _pwMfaCodeBox = new TextBox
        {
            Header = "动态验证码（已启用时填写）",
            PlaceholderText = "6 位动态验证码",
            MaxLength = 6,
            Visibility = Visibility.Collapsed,
        };
        _newPasswordBox = new PasswordBox { Header = "新密码" };
        Grid.SetColumn(_newPasswordBox, 1);
        pwGrid.Children.Add(_oldPasswordBox);
        pwGrid.Children.Add(_newPasswordBox);
        changePwBtn.Click += async (_, _) =>
        {
            if (string.IsNullOrEmpty(_oldPasswordBox.Password) || string.IsNullOrEmpty(_newPasswordBox.Password)) return;
            try
            {
                var code = _pwMfaCodeBox.Text.Trim();
                await _panel.ChangePasswordAsync(
                    _oldPasswordBox.Password,
                    _newPasswordBox.Password,
                    string.IsNullOrEmpty(code) ? null : code
                );
                _oldPasswordBox.Password = "";
                _pwMfaCodeBox.Text = "";
                _newPasswordBox.Password = "";
                ShowFeedback("密码已更新。", error: false);
            }
            catch (Exception ex)
            {
                ShowFeedback(ex.Message, error: true);
            }
        };
        pwStack.Children.Add(pwGrid);
        pwStack.Children.Add(_pwMfaCodeBox);
        outer.Children.Add(NetoUiFactory.CreateCard(pwStack));

        var mfaStack = new StackPanel { Spacing = 8 };
        mfaStack.Children.Add(NetoUiFactory.CreateInlineCardHeader("两步验证"));
        _mfaBody = new StackPanel { Spacing = 8 };
        mfaStack.Children.Add(_mfaBody);
        _mfaCard = NetoUiFactory.CreateCard(mfaStack);
        _mfaCard.Visibility = Visibility.Collapsed;
        outer.Children.Add(_mfaCard);

        var oauthStack = new StackPanel { Spacing = 8 };
        oauthStack.Children.Add(NetoUiFactory.CreateInlineCardHeader("第三方账号绑定"));
        _oauthBody = new StackPanel { Spacing = 8 };
        oauthStack.Children.Add(_oauthBody);
        _oauthCard = NetoUiFactory.CreateCard(oauthStack);
        _oauthCard.Visibility = Visibility.Collapsed;
        outer.Children.Add(_oauthCard);

        Root = NetoUiFactory.CreateCenteredScrollViewer(outer);
    }

    public void ApplySnapshot(PanelStateSnapshot snap)
    {
        _emailText.Text = string.IsNullOrEmpty(snap.Email) ? snap.AppName : snap.Email;
        var summary = string.IsNullOrEmpty(snap.SubscriptionSummary) ? "暂无套餐用量" : snap.SubscriptionSummary;
        _quotaText.Text = $"{summary} · 到期：{XbFormat.UnixDate(snap.ExpiredAtUnix)}";
        _balanceText.Text = $"余额 {XbFormat.Money(snap.Balance, snap.CurrencySymbol, snap.CurrencyUnit)} · 佣金 {XbFormat.Money(snap.CommissionBalance, snap.CurrencySymbol, snap.CurrencyUnit)}";
        _portalBtn.Visibility = snap.PaymentEnabled ? Visibility.Visible : Visibility.Collapsed;
        _newPasswordBox.Header = snap.IsPxBoard ? "新密码（至少 10 位）" : "新密码";
        _pwMfaCodeBox.Visibility = snap.IsPxBoard && snap.MfaEnabled ? Visibility.Visible : Visibility.Collapsed;

        var showRedeem = !snap.IsPxBoard || snap.Extensions.Contains("redeem");
        var showInvite = !snap.IsPxBoard || snap.Extensions.Contains("affiliate");
        var showTickets = !snap.IsPxBoard || snap.Extensions.Contains("support");
        var showRecharge = snap.IsPxBoard && snap.PaymentEnabled && snap.Extensions.Contains("commerce") && snap.PaymentChannels.Count > 0;
        var showMfa = snap.IsPxBoard;
        var showOAuth = snap.IsPxBoard && snap.Extensions.Contains("oauth") && (snap.OAuthProviders.Count > 0 || snap.OAuthBindings.Count > 0);

        _redeemCard.Visibility = showRedeem ? Visibility.Visible : Visibility.Collapsed;
        _inviteCard.Visibility = showInvite ? Visibility.Visible : Visibility.Collapsed;
        _ticketsSection.Visibility = showTickets ? Visibility.Visible : Visibility.Collapsed;
        _rechargeCard.Visibility = showRecharge ? Visibility.Visible : Visibility.Collapsed;
        _mfaCard.Visibility = showMfa ? Visibility.Visible : Visibility.Collapsed;
        _oauthCard.Visibility = showOAuth ? Visibility.Visible : Visibility.Collapsed;

        var sig = string.Join("|",
            snap.IsPxBoard,
            snap.PaymentEnabled,
            snap.SubscribeUrl,
            snap.MfaEnabled,
            _mfaSetup?.Secret ?? "",
            snap.OAuthBindPending,
            string.Join(",", snap.Extensions),
            string.Join(",", snap.PaymentChannels.Select(c => c.Id + ":" + c.Name)),
            string.Join(",", snap.OAuthProviders.Select(p => p.Id + ":" + p.Label)),
            string.Join(",", snap.OAuthBindings.Select(b => b.Id + ":" + b.RequiresPassword)),
            snap.ReferralUrl,
            snap.InviteCommissionRate,
            string.Join(",", snap.InviteCodes),
            string.Join(",", snap.Notices.Select(n => n.Id + ":" + n.Title)),
            string.Join(",", snap.Tickets.Select(t => t.Id + ":" + t.StatusText + ":" + t.Messages.Count))
        );
        if (_lastSig == sig) return;
        _lastSig = sig;

        // Subscription URL
        _subLinkContainer.Children.Clear();
        if (!string.IsNullOrEmpty(snap.SubscribeUrl))
        {
            _subLinkContainer.Children.Add(BuildCopyRow(snap.SubscribeUrl));
            _subLinkCard.Visibility = Visibility.Visible;
        }
        else
        {
            _subLinkCard.Visibility = Visibility.Collapsed;
        }

        // Recharge channels
        if (showRecharge)
        {
            _rechargeChannels = snap.PaymentChannels.Select(c => (c.Id, c.Name)).ToList();
            _rechargeChannelCombo.Items.Clear();
            foreach (var (_, name) in _rechargeChannels) _rechargeChannelCombo.Items.Add(name);
            if (_rechargeChannels.Count > 0) _rechargeChannelCombo.SelectedIndex = 0;
        }

        if (showMfa) RebuildMfaSection(snap);
        if (showOAuth) RebuildOAuthSection(snap);

        // Invite section
        _invitePanel.Children.Clear();
        _invitePanel.Children.Add(new TextBlock
        {
            Text = snap.InviteCommissionRate > 0 ? $"返利比例 {snap.InviteCommissionRate}%" : "推广邀请链接与邀请码",
            FontSize = 12,
            Foreground = NetoUiFactory.BrushResource("TextFillColorSecondaryBrush", Colors.Gray),
        });
        if (!string.IsNullOrEmpty(snap.ReferralUrl))
        {
            _invitePanel.Children.Add(BuildCopyRow(snap.ReferralUrl));
        }
        foreach (var code in snap.InviteCodes)
        {
            var link = string.IsNullOrEmpty(snap.BaseUrl) ? code : $"{snap.BaseUrl}/#/register?code={code}";
            _invitePanel.Children.Add(BuildCopyRow(link));
        }
        if (!snap.IsPxBoard)
        {
            var genBtn = new Button { Content = "生成邀请码" };
            genBtn.Click += async (_, _) => await _panel.GenerateInviteAsync();
            _invitePanel.Children.Add(genBtn);
        }

        // Notices section
        _noticesPanel.Children.Clear();
        if (snap.Notices.Count > 0)
        {
            _noticesPanel.Children.Add(NetoUiFactory.CreateSectionHeader("站点公告"));
            foreach (var notice in snap.Notices.Take(6))
            {
                var ns = new StackPanel { Spacing = 4 };
                ns.Children.Add(new TextBlock { Text = notice.Title, FontWeight = FontWeights.SemiBold });
                var body = XbFormat.PlainText(notice.Content);
                if (!string.IsNullOrEmpty(body))
                {
                    ns.Children.Add(new TextBlock
                    {
                        Text = body,
                        FontSize = 12,
                        TextWrapping = TextWrapping.Wrap,
                        Foreground = NetoUiFactory.BrushResource("TextFillColorSecondaryBrush", Colors.Gray),
                    });
                }
                _noticesPanel.Children.Add(NetoUiFactory.CreateCard(ns));
            }
        }

        // Tickets list
        _ticketsList.Children.Clear();
        foreach (var ticket in snap.Tickets)
        {
            var tRow = new Grid { ColumnSpacing = 8 };
            tRow.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            tRow.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            tRow.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            tRow.Children.Add(new TextBlock
            {
                Text = ticket.Subject,
                VerticalAlignment = VerticalAlignment.Center,
                FontWeight = FontWeights.SemiBold,
            });
            var st = new TextBlock
            {
                Text = ticket.StatusText,
                FontSize = 12,
                VerticalAlignment = VerticalAlignment.Center,
                Foreground = NetoUiFactory.BrushResource("TextFillColorSecondaryBrush", Colors.Gray),
            };
            var viewBtn = new Button { Content = "查看" };
            var tid = ticket.Id;
            viewBtn.Click += async (_, _) => await LoadTicketDetailAsync(tid);
            Grid.SetColumn(st, 1);
            Grid.SetColumn(viewBtn, 2);
            tRow.Children.Add(st);
            tRow.Children.Add(viewBtn);
            _ticketsList.Children.Add(NetoUiFactory.CreateCard(tRow, new Thickness(12, 8, 12, 8)));
        }
    }

    private static Grid BuildCopyRow(string value)
    {
        var g = new Grid { ColumnSpacing = 8 };
        g.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        g.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        var box = new TextBox { Text = value, IsReadOnly = true };
        var copyBtn = new Button { Content = "复制" };
        copyBtn.Click += async (_, _) =>
        {
            if (NetoWindowHelper.SetClipboardText(value))
            {
                copyBtn.Content = "已复制";
                await Task.Delay(1000);
                copyBtn.Content = "复制";
            }
        };
        Grid.SetColumn(copyBtn, 1);
        g.Children.Add(box);
        g.Children.Add(copyBtn);
        return g;
    }

    private void RebuildMfaSection(PanelStateSnapshot snap)
    {
        _mfaBody.Children.Clear();
        if (snap.MfaEnabled)
        {
            _mfaSetup = null;
            _mfaBody.Children.Add(new TextBlock
            {
                Text = "已启用 · 登录和敏感操作需输入 6 位动态验证码",
                FontSize = 12,
                Foreground = NetoUiFactory.BrushResource("TextFillColorSecondaryBrush", Colors.Gray),
            });
            var pwBox = new PasswordBox { Header = "当前登录密码" };
            var codeBox = new TextBox { Header = "6 位动态验证码", MaxLength = 6 };
            var disableBtn = new Button { Content = "关闭两步验证", HorizontalAlignment = HorizontalAlignment.Right };
            disableBtn.Click += async (_, _) =>
            {
                if (string.IsNullOrEmpty(pwBox.Password) || codeBox.Text.Trim().Length != 6) return;
                disableBtn.IsEnabled = false;
                try
                {
                    await _panel.MfaDisableAsync(pwBox.Password, codeBox.Text.Trim());
                    ShowFeedback("已关闭两步验证。", error: false);
                }
                catch (Exception ex)
                {
                    ShowFeedback(ex.Message, error: true);
                }
                finally
                {
                    disableBtn.IsEnabled = true;
                }
            };
            _mfaBody.Children.Add(pwBox);
            _mfaBody.Children.Add(codeBox);
            _mfaBody.Children.Add(disableBtn);
            return;
        }

        if (_mfaSetup == null)
        {
            _mfaBody.Children.Add(new TextBlock
            {
                Text = "未启用 · 使用身份验证器生成 6 位动态验证码",
                FontSize = 12,
                Foreground = NetoUiFactory.BrushResource("TextFillColorSecondaryBrush", Colors.Gray),
            });
            var setupBtn = new Button { Content = "设置两步验证" };
            NetoUiFactory.ApplyPrimaryButton(setupBtn, true);
            setupBtn.Click += async (_, _) =>
            {
                setupBtn.IsEnabled = false;
                try
                {
                    _mfaSetup = await _panel.MfaSetupAsync();
                    _lastSig = "";
                    ApplySnapshot(_panel.Snapshot);
                }
                catch (Exception ex)
                {
                    ShowFeedback(ex.Message, error: true);
                }
                finally
                {
                    setupBtn.IsEnabled = true;
                }
            };
            _mfaBody.Children.Add(setupBtn);
            return;
        }

        _mfaBody.Children.Add(new TextBlock
        {
            Text = "在验证器中添加以下密钥或导入链接，再输入生成的 6 位验证码完成启用：",
            FontSize = 12,
            TextWrapping = TextWrapping.Wrap,
            Foreground = NetoUiFactory.BrushResource("TextFillColorSecondaryBrush", Colors.Gray),
        });
        _mfaBody.Children.Add(BuildCopyRow(_mfaSetup.Secret));
        if (!string.IsNullOrEmpty(_mfaSetup.OtpauthUrl))
        {
            _mfaBody.Children.Add(BuildCopyRow(_mfaSetup.OtpauthUrl));
        }
        var enablePwBox = new PasswordBox { Header = "当前登录密码" };
        var enableCodeBox = new TextBox { Header = "6 位动态验证码", MaxLength = 6 };
        var btnRow = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 8, HorizontalAlignment = HorizontalAlignment.Right };
        var cancelBtn = new Button { Content = "取消" };
        var confirmBtn = new Button { Content = "确认启用" };
        NetoUiFactory.ApplyPrimaryButton(confirmBtn, true);
        cancelBtn.Click += (_, _) =>
        {
            _mfaSetup = null;
            _lastSig = "";
            ApplySnapshot(_panel.Snapshot);
        };
        confirmBtn.Click += async (_, _) =>
        {
            if (string.IsNullOrEmpty(enablePwBox.Password) || enableCodeBox.Text.Trim().Length != 6) return;
            confirmBtn.IsEnabled = false;
            try
            {
                await _panel.MfaEnableAsync(enablePwBox.Password, enableCodeBox.Text.Trim());
                _mfaSetup = null;
                ShowFeedback("已启用两步验证。", error: false);
            }
            catch (Exception ex)
            {
                ShowFeedback(ex.Message, error: true);
            }
            finally
            {
                confirmBtn.IsEnabled = true;
            }
        };
        btnRow.Children.Add(cancelBtn);
        btnRow.Children.Add(confirmBtn);
        _mfaBody.Children.Add(enablePwBox);
        _mfaBody.Children.Add(enableCodeBox);
        _mfaBody.Children.Add(btnRow);
    }

    private void RebuildOAuthSection(PanelStateSnapshot snap)
    {
        _oauthBody.Children.Clear();
        var boundProviderIds = snap.OAuthBindings.Select(b => b.ProviderId).ToHashSet();
        PasswordBox? unbindPwBox = null;
        TextBox? unbindCodeBox = null;
        if (snap.OAuthBindings.Any(b => b.RequiresPassword))
        {
            unbindPwBox = new PasswordBox { Header = "解绑最后一个登录方式时需输入当前登录密码" };
            _oauthBody.Children.Add(unbindPwBox);
            if (snap.MfaEnabled)
            {
                unbindCodeBox = new TextBox { Header = "6 位动态验证码", MaxLength = 6 };
                _oauthBody.Children.Add(unbindCodeBox);
            }
        }

        foreach (var b in snap.OAuthBindings)
        {
            var row = new Grid { ColumnSpacing = 8 };
            row.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            row.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            var title = string.IsNullOrEmpty(b.ProviderName) ? b.ProviderId : b.ProviderName;
            var text = string.IsNullOrEmpty(b.DisplayName) ? $"{title} · 已绑定" : $"{title} · {b.DisplayName}";
            row.Children.Add(new TextBlock { Text = text, VerticalAlignment = VerticalAlignment.Center });
            var unbindBtn = new Button { Content = "解除绑定" };
            var binding = b;
            unbindBtn.Click += async (_, _) =>
            {
                var pw = unbindPwBox?.Password ?? "";
                var code = unbindCodeBox?.Text.Trim() ?? "";
                if (binding.RequiresPassword && string.IsNullOrEmpty(pw))
                {
                    ShowFeedback("解除最后一个登录绑定前请输入当前登录密码。", error: true);
                    return;
                }
                unbindBtn.IsEnabled = false;
                try
                {
                    await _panel.UnbindOAuthAsync(binding.Id, pw, code);
                    ShowFeedback("已解除第三方账号绑定。", error: false);
                }
                catch (Exception ex)
                {
                    ShowFeedback(ex.Message, error: true);
                }
                finally
                {
                    unbindBtn.IsEnabled = true;
                }
            };
            Grid.SetColumn(unbindBtn, 1);
            row.Children.Add(unbindBtn);
            _oauthBody.Children.Add(row);
        }

        if (snap.OAuthBindPending)
        {
            var cancelBtn = new Button { Content = "取消绑定授权" };
            cancelBtn.Click += async (_, _) => await _panel.CancelBindOAuthAsync();
            _oauthBody.Children.Add(cancelBtn);
            return;
        }

        var unbound = snap.OAuthProviders.Where(p => !boundProviderIds.Contains(p.Id)).ToList();
        if (unbound.Count > 0)
        {
            var btnWrap = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 8 };
            foreach (var p in unbound)
            {
                var pid = p.Id;
                var btn = new Button { Content = $"绑定 {p.Label}" };
                btn.Click += async (_, _) =>
                {
                    ShowFeedback("请在已打开的系统浏览器中完成第三方账号授权。", error: false);
                    try
                    {
                        await _panel.BindOAuthAsync(pid);
                        ShowFeedback("第三方账号已绑定。", error: false);
                    }
                    catch (Exception ex)
                    {
                        ShowFeedback(ex.Message, error: true);
                    }
                };
                btnWrap.Children.Add(btn);
            }
            _oauthBody.Children.Add(btnWrap);
        }
    }

    private async Task LoadTicketDetailAsync(string ticketId)
    {
        _selectedTicketId = ticketId;
        var ticket = _panel.Snapshot.Tickets.FirstOrDefault(t => t.Id == ticketId);
        _ticketDetailTitle.Text = ticket != null ? $"{ticket.Subject}（{ticket.StatusText}）" : $"工单 #{ticketId}";
        var closed = ticket?.Closed ?? false;
        _ticketReplyBox.Visibility = closed ? Visibility.Collapsed : Visibility.Visible;
        _ticketReplyBtn.Visibility = closed ? Visibility.Collapsed : Visibility.Visible;
        _ticketCloseBtn.Visibility = closed ? Visibility.Collapsed : Visibility.Visible;
        _ticketMessagesPanel.Children.Clear();
        try
        {
            var msgs = await _panel.FetchTicketMessagesAsync(ticketId);
            foreach (var m in msgs)
            {
                var ms = new StackPanel { Spacing = 2 };
                ms.Children.Add(new TextBlock
                {
                    Text = string.IsNullOrEmpty(m.CreatedAt) ? m.Author : $"{m.Author} · {m.CreatedAt}",
                    FontSize = 11,
                    Foreground = NetoUiFactory.BrushResource("TextFillColorSecondaryBrush", Colors.Gray),
                });
                ms.Children.Add(new TextBlock { Text = m.Message, TextWrapping = TextWrapping.Wrap });
                _ticketMessagesPanel.Children.Add(NetoUiFactory.CreateCard(ms, new Thickness(10, 6, 10, 6)));
            }
        }
        catch (Exception ex)
        {
            ShowFeedback(ex.Message, error: true);
        }
        _ticketDetailCard.Visibility = Visibility.Visible;
    }

    private void ShowFeedback(string text, bool error)
    {
        _feedbackText.Text = text;
        _feedbackText.Foreground = new SolidColorBrush(error
            ? Color.FromArgb(255, 216, 82, 76)
            : Color.FromArgb(255, 46, 184, 128));
        _feedbackCard.Visibility = Visibility.Visible;
    }
}
