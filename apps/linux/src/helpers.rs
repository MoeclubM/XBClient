#![cfg(target_os = "linux")]

pub fn plain_text(raw: &str) -> String {
    let mut out = String::with_capacity(raw.len());
    let mut in_tag = false;
    let replaced = raw
        .replace("<br>", "\n")
        .replace("<br/>", "\n")
        .replace("<br />", "\n")
        .replace("</p>", "\n")
        .replace("</li>", "\n");
    for ch in replaced.chars() {
        match ch {
            '<' => in_tag = true,
            '>' => in_tag = false,
            _ if !in_tag => out.push(ch),
            _ => {}
        }
    }
    out.replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .trim()
        .to_string()
}

pub fn describe_block_reason(reason: &str) -> (&'static str, &'static str) {
    match reason {
        "no_plan" => ("暂无可用订阅", "当前账户尚未开通套餐，请前往套餐页购买或使用兑换码开通。"),
        "traffic_exceeded" => ("套餐流量已用尽", "当前计费周期流量已耗尽，请重置流量或续订套餐。"),
        "suspended" => ("账户不可用", "当前账户连接权限已被暂停，请提交工单联系支持。"),
        _ => ("订阅已到期", "当前套餐已到期，请续费或使用兑换码后继续连接。"),
    }
}
