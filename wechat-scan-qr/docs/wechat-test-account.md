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

3. Check server logs:
   - The server will log the domain being used
   - Verify the domain matches what you configured in Step 3

## Common Issues

### Issue 1: "invalid url rid: xxx"

**Cause**: JS接口安全域名 not configured or doesn't match

**Solution**:
1. Check server logs for "Domain:" output
2. Make sure this domain is configured in test account settings
3. Domain must match exactly (no http://, no port)

Example server output:
```
==================================================
WeChat Config Request
==================================================
Page URL: http://192.168.1.100:3080/
Domain: 192.168.1.100
Port: 3080
```

In this case, you need to configure `192.168.1.100` in test account.

### Issue 2: Cannot access from phone

**Check**:
1. Phone and computer are on same WiFi network
2. Windows firewall allows port 3080
3. Server is running

### Issue 3: "invalid signature"

**Cause**: URL mismatch or time sync issue

**Solution**:
1. Make sure system time is accurate
2. Check that the URL in browser matches the URL sent to server
3. Clear browser cache and retry

## Test Account Limitations

- Test accounts have most API permissions enabled
- No ICP filing required
- Can use IP addresses for testing
- May have some rate limits
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
