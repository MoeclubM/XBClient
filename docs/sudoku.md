# Sudoku

Android 与 Electron 节点列表及连接流程支持 `type=sudoku`。
更新 Xboard 和 Xboard-XBClient 0.0.37 后，节点接口下发每用户 UUID `key`、
AEAD、ASCII、填充比例、经典/packed 下行、自定义表与 HTTPMask 配置。
共享 Rust 核心导入完整配置，Android/Windows/Linux 均使用同一 Aerion 实现。

支持 TCP、UDP over TCP、HTTPMask legacy / WebSocket 和验证证书的 HTTPS 反代。
HTTPMask stream/poll/auto 暂未实现，连接配置会明确报错。
开启 mux 时使用官方 mux 帧，目前每条本地连接使用独立隧道。
