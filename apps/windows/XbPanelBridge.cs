using System.Runtime.InteropServices;
using System.Text.Json;
using System.Text.Json.Nodes;
using Microsoft.UI.Dispatching;

namespace XBClient.Windows;

public static class XbPanelBridge
{
    private const string DllName = "xbclient_ffi";

    [DllImport(DllName, CallingConvention = CallingConvention.Cdecl)]
    private static extern IntPtr xbclient_panel_init(IntPtr dataDir);

    [DllImport(DllName, CallingConvention = CallingConvention.Cdecl)]
    private static extern IntPtr xbclient_panel_call(IntPtr method, IntPtr paramsJson);

    [DllImport(DllName, CallingConvention = CallingConvention.Cdecl)]
    private static extern void neto_free_string(IntPtr ptr);

    public static PanelStateSnapshot Init(string dataDir)
    {
        var dirPtr = Marshal.StringToCoTaskMemUTF8(dataDir);
        try
        {
            var res = ConsumeEnvelope(xbclient_panel_init(dirPtr));
            return res["panel"].Deserialize<PanelStateSnapshot>() ?? new PanelStateSnapshot();
        }
        finally
        {
            Marshal.FreeCoTaskMem(dirPtr);
        }
    }

    public static JsonNode CallSync(string method, object? parameters = null)
    {
        var jsonText = parameters switch
        {
            null => "{}",
            string raw => raw,
            JsonNode node => node.ToJsonString(),
            _ => JsonSerializer.Serialize(parameters),
        };
        var mPtr = Marshal.StringToCoTaskMemUTF8(method);
        var pPtr = Marshal.StringToCoTaskMemUTF8(jsonText);
        try
        {
            return ConsumeEnvelope(xbclient_panel_call(mPtr, pPtr));
        }
        finally
        {
            Marshal.FreeCoTaskMem(mPtr);
            Marshal.FreeCoTaskMem(pPtr);
        }
    }

    public static Task<JsonNode> CallAsync(string method, object? parameters = null) =>
        Task.Run(() => CallSync(method, parameters));

    private static JsonNode ConsumeEnvelope(IntPtr ptr)
    {
        if (ptr == IntPtr.Zero)
        {
            throw new InvalidOperationException("xbclient_ffi returned null pointer");
        }
        string raw;
        try
        {
            raw = Marshal.PtrToStringUTF8(ptr) ?? "{}";
        }
        finally
        {
            neto_free_string(ptr);
        }
        var root = JsonNode.Parse(raw)?.AsObject()
            ?? throw new InvalidOperationException("Invalid JSON returned from xbclient_ffi");
        if (root["ok"]?.GetValue<bool>() != true)
        {
            var err = root["error"]?.GetValue<string>() ?? "Unknown panel error";
            throw new InvalidOperationException(err);
        }
        return root["result"] ?? new JsonObject();
    }
}

public sealed class XbPanelService
{
    private readonly DispatcherQueue _dispatcher;
    private readonly Func<Task> _refreshNeto;

    public PanelStateSnapshot Snapshot { get; private set; }
    public bool Busy { get; private set; }
    public string? LastError { get; private set; }

    public event Action<PanelStateSnapshot>? SnapshotChanged;

    public XbPanelService(PanelStateSnapshot initial, DispatcherQueue dispatcher, Func<Task> refreshNeto)
    {
        Snapshot = initial;
        _dispatcher = dispatcher;
        _refreshNeto = refreshNeto;
    }

    public Task InitOrSyncAsync() => RunSnapshotOpAsync("init_or_sync", null, syncNeto: true);
    public Task SyncAllAsync() => RunSnapshotOpAsync("sync_all", null, syncNeto: true);
    public Task LogoutAsync() => RunSnapshotOpAsync("logout", null, syncNeto: true);

    public Task LoginPasswordAsync(string email, string password, string? code = null) =>
        RunSnapshotOpAsync("login_password", new { email, password, code }, syncNeto: true);

    public Task RegisterAsync(string email, string password, string inviteCode, string emailCode) =>
        RunSnapshotOpAsync("register_account", new
        {
            email,
            password,
            invite_code = inviteCode,
            email_code = emailCode,
        }, syncNeto: true);

    public async Task SendEmailCodeAsync(string email)
    {
        SetBusy(true, null);
        try
        {
            await XbPanelBridge.CallAsync("send_email_code", new { email });
            SetBusy(false, null);
        }
        catch (Exception ex)
        {
            SetBusy(false, ex.Message);
            throw;
        }
    }

    public async Task BrowserLoginAsync(string? providerId = null)
    {
        EnqueueUi(() =>
        {
            LastError = null;
            Snapshot.BrowserLoginPending = true;
            SnapshotChanged?.Invoke(Snapshot);
        });
        try
        {
            var res = await XbPanelBridge.CallAsync("browser_login", new { provider_id = providerId });
            var snap = res.Deserialize<PanelStateSnapshot>() ?? Snapshot;
            await _refreshNeto();
            EnqueueUi(() =>
            {
                Snapshot = snap;
                SnapshotChanged?.Invoke(Snapshot);
            });
        }
        catch (Exception ex)
        {
            EnqueueUi(() =>
            {
                Snapshot.BrowserLoginPending = false;
                LastError = ex.Message;
                SnapshotChanged?.Invoke(Snapshot);
            });
        }
    }

    public Task CancelBrowserLoginAsync() => RunSnapshotOpAsync("cancel_browser_login", null, syncNeto: false);

    public async Task<string?> PurchasePlanAsync(string planId, string periodId, string coupon, string channelId)
    {
        SetBusy(true, null);
        try
        {
            var res = await XbPanelBridge.CallAsync("purchase_plan", new
            {
                plan_id = planId,
                period_id = periodId,
                coupon,
                channel_id = channelId,
            });
            var redirect = res["redirect_url"]?.GetValue<string>();
            var snap = res["snapshot"].Deserialize<PanelStateSnapshot>();
            await _refreshNeto();
            EnqueueUi(() =>
            {
                Busy = false;
                if (snap != null) Snapshot = snap;
                SnapshotChanged?.Invoke(Snapshot);
            });
            return redirect;
        }
        catch (Exception ex)
        {
            SetBusy(false, ex.Message);
            throw;
        }
    }

    public async Task<string> RedeemCodeAsync(string code)
    {
        SetBusy(true, null);
        try
        {
            var res = await XbPanelBridge.CallAsync("redeem_code", new { code });
            var msg = res["message"]?.GetValue<string>() ?? "兑换已完成";
            var snap = res["snapshot"].Deserialize<PanelStateSnapshot>();
            await _refreshNeto();
            EnqueueUi(() =>
            {
                Busy = false;
                if (snap != null) Snapshot = snap;
                SnapshotChanged?.Invoke(Snapshot);
            });
            return msg;
        }
        catch (Exception ex)
        {
            SetBusy(false, ex.Message);
            throw;
        }
    }

    public Task CreateTicketAsync(string subject, string content) =>
        RunSnapshotOpAsync("create_ticket", new { subject, content }, syncNeto: false);

    public Task ReplyTicketAsync(string id, string message) =>
        RunSnapshotOpAsync("reply_ticket", new { id, message }, syncNeto: false);

    public Task CloseTicketAsync(string id) =>
        RunSnapshotOpAsync("close_ticket", new { id }, syncNeto: false);

    public async Task<List<TicketMessage>> FetchTicketMessagesAsync(string id)
    {
        var res = await XbPanelBridge.CallAsync("fetch_ticket_messages", new { id });
        return res.Deserialize<List<TicketMessage>>() ?? [];
    }

    public Task GenerateInviteAsync() => RunSnapshotOpAsync("generate_invite", null, syncNeto: false);

    public Task OpenPortalAsync(string path = "/user") =>
        XbPanelBridge.CallAsync("open_portal", new { path });

    public Task ChangePasswordAsync(string oldPassword, string newPassword) =>
        XbPanelBridge.CallAsync("change_password", new { old_password = oldPassword, new_password = newPassword });

    public Task OpenUrlAsync(string url) =>
        XbPanelBridge.CallAsync("open_url", new { url });

    private async Task RunSnapshotOpAsync(string method, object? parameters, bool syncNeto)
    {
        SetBusy(true, null);
        try
        {
            var res = await XbPanelBridge.CallAsync(method, parameters);
            var snap = res.Deserialize<PanelStateSnapshot>() ?? Snapshot;
            if (syncNeto) await _refreshNeto();
            EnqueueUi(() =>
            {
                Busy = false;
                Snapshot = snap;
                SnapshotChanged?.Invoke(Snapshot);
            });
        }
        catch (Exception ex)
        {
            SetBusy(false, ex.Message);
        }
    }

    private void SetBusy(bool busy, string? error)
    {
        EnqueueUi(() =>
        {
            Busy = busy;
            LastError = error;
            SnapshotChanged?.Invoke(Snapshot);
        });
    }

    private void EnqueueUi(Action action)
    {
        if (_dispatcher.HasThreadAccess) action();
        else _dispatcher.TryEnqueue(() => action());
    }
}
