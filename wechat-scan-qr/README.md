# WeChat QR Code Scanner Demo

这是一个可以在微信浏览器中调起微信扫一扫功能的 Web 应用 Demo。

## 功能特点

- ✅ 调起微信扫一扫功能
- ✅ 显示扫描结果
- ✅ 自动识别内容类型（URL、JSON、邮箱、电话等）
- ✅ 支持复制结果
- ✅ 支持手动输入测试
- ✅ Demo 模式（无需配置即可预览）

## 快速开始

### 1. 安装依赖

```bash
npm install
```

### 2. 配置微信公众号（必须）

如果要使用真实的微信扫码功能，需要配置微信公众号信息：

1. 复制 `.env.example` 为 `.env`：
   ```bash
   cp .env.example .env
   ```

2. 编辑 `.env` 文件，填入你的微信公众号信息：
   ```
   WECHAT_APP_ID=你的AppID
   WECHAT_APP_SECRET=你的AppSecret
   ```

3. **配置 JS 接口安全域名**（重要！）：
   - 登录 [微信公众平台](https://mp.weixin.qq.com)
   - 进入：**公众号设置** → **功能设置** → **JS接口安全域名**
   - 点击"设置"，下载验证文件 `MP_verify_xxxxx.txt`
   - 将验证文件放到 `public/` 目录下
   - 在域名配置中填入你的域名（如 `example.com` 或 `192.168.1.100`）
   - **注意**：域名格式为 `example.com`，不要加 `http://` 或端口号
   - 对于 IP 地址测试：直接填 `192.168.1.100`（不要加端口）

### 3. 启动服务

```bash
npm start
```

服务将在 http://localhost:3000 启动。

### 4. 在微信中访问

将服务部署到公网服务器，并在微信公众平台配置好安全域名后，在微信中访问应用 URL 即可使用。

## Demo 模式

如果没有配置微信公众号信息，应用会以 Demo 模式运行：

- 可以预览界面和交互流程
- 无法使用真实的微信扫码功能
- 可以通过手动输入来测试结果显示

## 项目结构

```
wechat-scan-qr/
├── server.js           # Express 服务器
├── package.json        # 项目配置
├── .env.example        # 环境变量示例
├── README.md           # 说明文档
└── public/
    ├── index.html      # 首页（扫码入口)
    └── result.html     # 结果页(显示扫描结果)
```

## 技术栈

- **后端**: Node.js + Express
- **微信 SDK**: [微信 JS-SDK](https://developers.weixin.qq.com/doc/offiaccount/OA_Web_Apps/JS-SDK.html)
- **前端**: 原生 HTML + CSS + JavaScript

## 微信公众号配置要求

要使用微信扫码功能，需要：

1. 拥有认证的微信公众号（订阅号或服务号）
2. 在公众平台配置 JS 接口安全域名
3. 将应用部署到配置的域名下
4. 服务器需要支持 HTTPS（微信要求）

## 本地测试

由于微信 JS-SDK 需要在微信浏览器中使用，本地开发测试可以使用：

1. **内网穿透工具**（如 ngrok、frp）将本地服务映射到公网
2. 使用微信开发者工具进行调试

## 常见问题

### 1. 提示"invalid signature"

- 检查 JS 接口安全域名是否配置正确
- 磀查域名格式是否正确（不带 http:// 和端口）
- 确认 URL 参数是完整的页面 URL
- 检查 AppID 和 AppSecret 是否正确

### 2. 扫码无反应

- 确认在微信浏览器中打开
- 检查公众号是否有扫码权限
- 查看控制台错误信息

### 3. 无法获取 access token

- 检查 AppID 和 AppSecret 是否正确
- 确认服务器 IP 是否在白名单中
- 检查网络连接

## License

MIT
