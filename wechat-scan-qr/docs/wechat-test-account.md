# WeChat Test Account Configuration Guide

This guide explains how to configure a WeChat test account (测试号) for local development with IP address.

## Step 1: Get Test Account

1. Visit: https://mp.weixin.qq.com/debug/cgi-bin/sandboxinfo
2. Scan the QR code with your WeChat to login
3. You'll see your test account credentials:
   - **appID**: wxXXXXXXXXXXXX
   - **appsecret**: XXXXXXXXXXXXXXXX

## Step 2: Update .env File

```bash
cp .env.example .env
```

Edit `.env` with your test account credentials:
```
WECHAT_APP_ID=wxXXXXXXXXXXXX
WECHAT_APP_SECRET=your_appsecret_here
PORT=3080
```

## Step 3: Configure JS Interface Security Domain

On the test account page:

1. Find **JS接口安全域名** section
2. Click "修改" (Modify)
3. Enter your IP address (e.g., `192.168.1.100`)
   - ⚠️ Do NOT include `http://` or port number
   - ⚠️ Just the IP: `192.168.1.100`
4. Click "确定" (Confirm)

**Note**: Test accounts do NOT require domain verification file (`MP_verify_xxxxx.txt`)

## Step 4: Expose WSL to Network

If running on WSL, follow the firewall configuration guide:

```bash
# See docs/config-wsl-firewall.md
```

Quick setup (run in PowerShell as Administrator):
```powershell
# Get WSL IP
$wslIp = (wsl hostname -I).Trim().Split()[0]

# Add port forwarding
netsh interface portproxy add v4tov4 address=0.0.0.0 port=3080 connectaddress=$wslIp connectport=3080

# Add firewall rule
New-NetFirewallRule -DisplayName "WSL Port 3080" -Direction Inbound -LocalPort 3080 -Protocol TCP -Action Allow
```

## Step 5: Get Your Windows IP

```powershell
ipconfig
```

Find your main network adapter (Ethernet or Wi-Fi) IPv4 address.

## Step 6: Test the Configuration

1. Start the server:
   ```bash
   npm start
   ```

2. On your phone (connected to same WiFi):
   - Open WeChat
   - Visit: `http://YOUR_WINDOWS_IP:3080`

## Test Account Limitations

- Test accounts have most API permissions enabled
- No ICP filing required
- Can use IP addresses for testing
- Perfect for development and testing

## Debug Mode

Enable debug mode in `public/index.html`:

```javascript
wx.config({
    debug: true,  // Set to true for debug mode
    appId: config.appId,
    ...
});
```

This will show detailed error messages in an alert box.
