using Microsoft.UI;
using Microsoft.UI.Text;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;
using Neto.Sdk.Windows.Views;
using Windows.ApplicationModel.DataTransfer;
using Windows.UI;

namespace XBClient.Windows.Views;

public sealed class AccountPageView
{
    public ScrollViewer Root { get; }
    private readonly XbPanelService _panel;
    private readonly TextBlock _emailText;
    private readonly TextBlock _quotaText;
    private readonly TextBlock _balanceText;
    private readonly Border _feedbackCard;
    private readonly TextBlock _feedbackText;
    private readonly TextBox _redeemBox;
    private readonly StackPanel _invitePanel;
    private readonly StackPanel _noticesPanel;
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
    private readonly PasswordBox _newPasswordBox;
    private string? _selectedTicketId;
    private string _lastSig = "";

    public AccountPageView(XbPanelService panel)
    {
        _panel = panel;

        var outer = new StackPanel
        {
            MaxWidth = 820,
            Spacing = 12,
            Padding = new Thickness(18, 12, 18, 24),
        };

        _feedbackText = new TextBlock { FontSize = 12, TextWrapping = TextWrapping.Wrap };
        _feedbackCard = NetoUiFactory.CreateCard(_feedbackText, new Thickness(12, 8, 12, 8));
        _feedbackCard.Visibility = Visibility.Collapsed;
        outer.Children.Add(_feedbackCard);

        // Account overview
        var accStack = new StackPanel { Spacing = 8 };
        _emailText = new TextBlock { FontSize = 15, FontWeight = FontWeights.SemiBold };
        _quotaText = new TextBlock { FontSize = 12, Foreground = NetoUiFactory.BrushResource("TextFillColorSecondaryBrush", Colors.Gray) };
        _balanceText = new TextBlock { FontSize = 12, Foreground = NetoUiFactory.BrushResource("TextFillColorSecondaryBrush", Colors.Gray) };
        var accBtns = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 8 };
        var syncBtn = new Button { Content = "同步账户" };
        var portalBtn = new Button { Content = "网页中心" };
        var logoutBtn = new Button { Content = "退出登录" };
        syncBtn.Click += async (_, _) => await _panel.SyncAllAsync();
        portalBtn.Click += async (_, _) => await _panel.OpenPortalAsync("/user");
        logoutBtn.Click += async (_, _) => await _panel.LogoutAsync();
        accBtns.Children.Add(syncBtn);
        accBtns.Children.Add(portalBtn);
        accBtns.Children.Add(logoutBtn);
        accStack.Children.Add(_emailText);
        accStack.Children.Add(_quotaText);
        accStack.Children.Add(_balanceText);
        accStack.Children.Add(accBtns);
        outer.Children.Add(NetoUiFactory.CreateCard(accStack));

        // Redeem card
        outer.Children.Add(NetoUiFactory.CreateSectionHeader("兑换码"));
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
        outer.Children.Add(NetoUiFactory.CreateCard(redeemGrid));

        // Invite & referral
        outer.Children.Add(NetoUiFactory.CreateSectionHeader("邀请返利"));
        _invitePanel = new StackPanel { Spacing = 8 };
        outer.Children.Add(NetoUiFactory.CreateCard(_invitePanel));

        // Notices
        _noticesPanel = new StackPanel { Spacing = 8 };
        outer.Children.Add(_noticesPanel);

        // Tickets
        outer.Children.Add(NetoUiFactory.CreateSectionHeader("工单支持"));
        var newTicketStack = new StackPanel { Spacing = 8 };
        _ticketSubjectBox = new TextBox { PlaceholderText = "工单主题" };
        _ticketContentBox = new TextBox
        {
            PlaceholderText = "详细描述遇到的问题",
            AcceptsReturn = true,
            TextWrapping = TextWrapping.Wrap,
            MinHeight = 68,
        };
        var createTicketBtn = new Button { Content = "提交工单", HorizontalAlignment = HorizontalAlignment.Right };
        NetoUiFactory.ApplyPrimaryButton(createTicketBtn, true);
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
        newTicketStack.Children.Add(createTicketBtn);
        outer.Children.Add(NetoUiFactory.CreateCard(newTicketStack));

        _ticketsList = new StackPanel { Spacing = 6 };
        outer.Children.Add(_ticketsList);

        var detailStack = new StackPanel { Spacing = 8 };
        _ticketDetailTitle = new TextBlock { FontWeight = FontWeights.SemiBold };
        _ticketMessagesPanel = new StackPanel { Spacing = 6 };
        _ticketReplyBox = new TextBox { PlaceholderText = "输入回复内容" };
        var detailBtns = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 8, HorizontalAlignment = HorizontalAlignment.Right };
        _ticketReplyBtn = new Button { Content = "发送回复" };
        NetoUiFactory.ApplyPrimaryButton(_ticketReplyBtn, true);
        _ticketCloseBtn = new Button { Content = "关闭工单" };
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
        detailBtns.Children.Add(_ticketCloseBtn);
        detailBtns.Children.Add(_ticketReplyBtn);
        detailStack.Children.Add(_ticketDetailTitle);
        detailStack.Children.Add(_ticketMessagesPanel);
        detailStack.Children.Add(_ticketReplyBox);
        detailStack.Children.Add(detailBtns);
        _ticketDetailCard = NetoUiFactory.CreateCard(detailStack);
        _ticketDetailCard.Visibility = Visibility.Collapsed;
        outer.Children.Add(_ticketDetailCard);

        // Password security
        outer.Children.Add(NetoUiFactory.CreateSectionHeader("修改密码"));
        var pwStack = new StackPanel { Spacing = 8 };
        _oldPasswordBox = new PasswordBox { Header = "当前密码" };
        _newPasswordBox = new PasswordBox { Header = "新密码" };
        var changePwBtn = new Button { Content = "更新密码", HorizontalAlignment = HorizontalAlignment.Right };
        changePwBtn.Click += async (_, _) =>
        {
            if (string.IsNullOrEmpty(_oldPasswordBox.Password) || string.IsNullOrEmpty(_newPasswordBox.Password)) return;
            try
            {
                await _panel.ChangePasswordAsync(_oldPasswordBox.Password, _newPasswordBox.Password);
                _oldPasswordBox.Password = "";
                _newPasswordBox.Password = "";
                ShowFeedback("密码已更新。", error: false);
            }
            catch (Exception ex)
            {
                ShowFeedback(ex.Message, error: true);
            }
        };
        pwStack.Children.Add(_oldPasswordBox);
        pwStack.Children.Add(_newPasswordBox);
        pwStack.Children.Add(changePwBtn);
        outer.Children.Add(NetoUiFactory.CreateCard(pwStack));

        Root = new ScrollViewer { Content = outer };
    }

    public void ApplySnapshot(PanelStateSnapshot snap)
    {
        _emailText.Text = string.IsNullOrEmpty(snap.Email) ? snap.AppName : snap.Email;
        var summary = string.IsNullOrEmpty(snap.SubscriptionSummary) ? "暂无套餐用量" : snap.SubscriptionSummary;
        _quotaText.Text = $"{summary} · 到期：{XbFormat.UnixDate(snap.ExpiredAtUnix)}";
        _balanceText.Text = $"余额 {XbFormat.Money(snap.Balance, snap.CurrencySymbol, snap.CurrencyUnit)} · 佣金 {XbFormat.Money(snap.CommissionBalance, snap.CurrencySymbol, snap.CurrencyUnit)}";

        var sig = string.Join("|",
            snap.IsPxBoard,
            snap.ReferralUrl,
            snap.InviteCommissionRate,
            string.Join(",", snap.InviteCodes),
            string.Join(",", snap.Notices.Select(n => n.Id + ":" + n.Title)),
            string.Join(",", snap.Tickets.Select(t => t.Id + ":" + t.StatusText + ":" + t.Messages.Count))
        );
        if (_lastSig == sig) return;
        _lastSig = sig;

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
        copyBtn.Click += (_, _) =>
        {
            var pkg = new DataPackage();
            pkg.SetText(value);
            Clipboard.SetContent(pkg);
        };
        Grid.SetColumn(copyBtn, 1);
        g.Children.Add(box);
        g.Children.Add(copyBtn);
        return g;
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
