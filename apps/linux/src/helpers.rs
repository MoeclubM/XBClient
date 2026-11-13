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
        "no_plan" => ("未开通套餐", "当前账户暂无有效套餐或流量包。"),
        "traffic_exceeded" => ("套餐流量已用尽", "当前计费周期流量已耗尽。"),
        "suspended" => ("账户已暂停", "当前账户连接权限已暂停。"),
        _ => ("订阅已到期", "当前套餐已到期。"),
    }
}
