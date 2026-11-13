using Microsoft.UI;
using Microsoft.UI.Text;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;
using Neto.Sdk.Windows.Views;
using Windows.UI;

namespace XBClient.Windows.Views;

public sealed class PlansPageView
{
    public ScrollViewer Root { get; }
    private readonly XbPanelService _panel;
    private readonly TextBlock _balanceText;
    private readonly Button _refreshBtn;
    private readonly Button _portalBtn;
    private readonly Border _checkoutOptionsCard;
    private readonly TextBox _couponBox;
    private readonly ComboBox _channelCombo;
    private readonly Border _feedbackCard;
    private readonly TextBlock _feedbackText;
    private readonly Grid _plansGrid;
    private List<(string Id, string Label)> _channelOptions = [];
    private string _lastSig = "";

    public PlansPageView(XbPanelService panel)
    {
        _panel = panel;

        var outer = new StackPanel
        {
            MaxWidth = 860,
            Spacing = 10,
            Padding = new Thickness(18, 8, 18, 24),
        };

        var headerGrid = new Grid { ColumnSpacing = 8 };
        headerGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        headerGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        headerGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });

        _balanceText = new TextBlock
        {
            VerticalAlignment = VerticalAlignment.Center,
            FontWeight = FontWeights.SemiBold,
        };
        _portalBtn = new Button { Content = "网页商店" };
        _portalBtn.Click += async (_, _) => await _panel.OpenPortalAsync(_panel.Snapshot.IsPxBoard ? "/user/plans" : "/plans");
        _refreshBtn = new Button { Content = "刷新" };
        _refreshBtn.Click += async (_, _) => await _panel.SyncAllAsync();

        Grid.SetColumn(_portalBtn, 1);
        Grid.SetColumn(_refreshBtn, 2);
        headerGrid.Children.Add(_balanceText);
        headerGrid.Children.Add(_portalBtn);
        headerGrid.Children.Add(_refreshBtn);
        outer.Children.Add(NetoUiFactory.CreateCard(headerGrid));

        var checkoutOptionsRow = new Grid { ColumnSpacing = 10 };
        checkoutOptionsRow.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        checkoutOptionsRow.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        _couponBox = new TextBox { Header = "优惠码（选填）" };
        _channelCombo = new ComboBox { Header = "支付方式", HorizontalAlignment = HorizontalAlignment.Stretch };
        Grid.SetColumn(_channelCombo, 1);
        checkoutOptionsRow.Children.Add(_couponBox);
        checkoutOptionsRow.Children.Add(_channelCombo);
        _checkoutOptionsCard = NetoUiFactory.CreateCard(checkoutOptionsRow);
        _checkoutOptionsCard.Visibility = Visibility.Collapsed;
        outer.Children.Add(_checkoutOptionsCard);

        _feedbackText = new TextBlock { FontSize = 12, TextWrapping = TextWrapping.Wrap };
        _feedbackCard = NetoUiFactory.CreateCard(_feedbackText, new Thickness(12, 8, 12, 8));
        _feedbackCard.Visibility = Visibility.Collapsed;
        outer.Children.Add(_feedbackCard);

        _plansGrid = new Grid { ColumnSpacing = 10, RowSpacing = 10 };
        _plansGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        _plansGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        outer.Children.Add(_plansGrid);

        Root = NetoUiFactory.CreateCenteredScrollViewer(outer);
    }

    public void ApplySnapshot(PanelStateSnapshot snap)
    {
        _balanceText.Text = $"账户余额 {XbFormat.Money(snap.Balance, snap.CurrencySymbol, snap.CurrencyUnit)}";
        _refreshBtn.IsEnabled = !_panel.Busy;
        _portalBtn.Visibility = snap.IsPxBoard && snap.PaymentEnabled ? Visibility.Visible : Visibility.Collapsed;

        var sig = string.Join("|",
            snap.IsPxBoard,
            snap.PaymentEnabled,
            snap.CurrencySymbol,
            snap.CurrencyUnit,
            string.Join(",", snap.PaymentChannels.Select(c => c.Id + ":" + c.Name)),
            string.Join(";", snap.Plans.Select(p => p.Id + ":" + p.Name + ":" + p.Periods.Count))
        );
        if (_lastSig == sig) return;
        _lastSig = sig;

        var showChannels = snap.PaymentEnabled && snap.PaymentChannels.Count > 0;
        _checkoutOptionsCard.Visibility = snap.Plans.Count > 0 ? Visibility.Visible : Visibility.Collapsed;
        _channelCombo.Visibility = showChannels ? Visibility.Visible : Visibility.Collapsed;
        _channelOptions = [("wallet", "余额支付")];
        if (showChannels)
        {
            foreach (var ch in snap.PaymentChannels) _channelOptions.Add((ch.Id, ch.Name));
        }
        _channelCombo.Items.Clear();
        foreach (var (_, label) in _channelOptions) _channelCombo.Items.Add(label);
        _channelCombo.SelectedIndex = 0;

        _plansGrid.Children.Clear();
        _plansGrid.RowDefinitions.Clear();
        if (snap.Plans.Count == 0)
        {
            _plansGrid.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
            var emptyCard = NetoUiFactory.CreateCard(new TextBlock
            {
                Text = "暂无可购买套餐",
                Foreground = NetoUiFactory.BrushResource("TextFillColorSecondaryBrush", Colors.Gray),
            });
            Grid.SetColumnSpan(emptyCard, 2);
            _plansGrid.Children.Add(emptyCard);
            return;
        }

        var totalRows = (snap.Plans.Count + 1) / 2;
        for (var r = 0; r < totalRows; r++) _plansGrid.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });

        for (var pIdx = 0; pIdx < snap.Plans.Count; pIdx++)
        {
            var plan = snap.Plans[pIdx];
            var cardStack = new StackPanel { Spacing = 8 };
            var topRow = new Grid();
            topRow.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            topRow.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            topRow.Children.Add(new TextBlock { Text = plan.Name, FontSize = 15, FontWeight = FontWeights.SemiBold });
            if (!string.IsNullOrWhiteSpace(plan.QuotaText))
            {
                var q = new TextBlock
                {
                    Text = plan.QuotaText,
                    FontSize = 12,
                    VerticalAlignment = VerticalAlignment.Center,
                    Foreground = NetoUiFactory.BrushResource("TextFillColorSecondaryBrush", Colors.Gray),
                };
                Grid.SetColumn(q, 1);
                topRow.Children.Add(q);
            }
            cardStack.Children.Add(topRow);

            var desc = XbFormat.PlainText(plan.Description);
            if (!string.IsNullOrEmpty(desc))
            {
                cardStack.Children.Add(new TextBlock
                {
                    Text = desc,
                    FontSize = 12,
                    TextWrapping = TextWrapping.Wrap,
                    Foreground = NetoUiFactory.BrushResource("TextFillColorSecondaryBrush", Colors.Gray),
                });
            }

            if (plan.Periods.Count > 0)
            {
                var actionRow = new Grid { ColumnSpacing = 8 };
                actionRow.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
                actionRow.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });

                var periodCombo = new ComboBox { HorizontalAlignment = HorizontalAlignment.Stretch };
                foreach (var per in plan.Periods)
                {
                    var priceStr = XbFormat.Money(per.PriceCents, snap.CurrencySymbol, snap.CurrencyUnit);
                    periodCombo.Items.Add($"{per.Label} · {priceStr}");
                }
                periodCombo.SelectedIndex = 0;

                var buyBtn = new Button { Content = "购买" };
                NetoUiFactory.ApplyPrimaryButton(buyBtn, true);
                var currentPlan = plan;
                buyBtn.Click += async (_, _) =>
                {
                    var idx = Math.Clamp(periodCombo.SelectedIndex, 0, currentPlan.Periods.Count - 1);
                    var period = currentPlan.Periods[idx];
                    var channelId = showChannels && _channelCombo.SelectedIndex >= 0 && _channelCombo.SelectedIndex < _channelOptions.Count
                        ? _channelOptions[_channelCombo.SelectedIndex].Id
                        : "wallet";
                    buyBtn.IsEnabled = false;
                    try
                    {
                        var redirect = await _panel.PurchasePlanAsync(currentPlan.Id, period.Id, _couponBox.Text.Trim(), channelId);
                        ShowFeedback(
                            string.IsNullOrEmpty(redirect) ? "套餐已开通并同步订阅。" : "已打开外部收银台，支付完成后请点击刷新。",
                            error: false
                        );
                    }
                    catch (Exception ex)
                    {
                        ShowFeedback(ex.Message, error: true);
                    }
                    finally
                    {
                        buyBtn.IsEnabled = true;
                    }
                };

                Grid.SetColumn(buyBtn, 1);
                actionRow.Children.Add(periodCombo);
                actionRow.Children.Add(buyBtn);
                cardStack.Children.Add(actionRow);
            }

            var card = NetoUiFactory.CreateCard(cardStack);
            card.VerticalAlignment = VerticalAlignment.Stretch;
            Grid.SetRow(card, pIdx / 2);
            Grid.SetColumn(card, pIdx % 2);
            if (pIdx == snap.Plans.Count - 1 && snap.Plans.Count % 2 == 1)
            {
                Grid.SetColumnSpan(card, 2);
            }
            _plansGrid.Children.Add(card);
        }
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
