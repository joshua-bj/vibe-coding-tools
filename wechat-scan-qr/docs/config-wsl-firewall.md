# WSL Firewall Configuration for Network Access

This guide explains how to expose WSL services to other machines on the same network.

## Prerequisites

- WSL2 installed on Windows
- PowerShell running as Administrator
- Service running on WSL (e.g., port 3080)

## Configuration Steps

### 1. Find WSL IP Address

Run in WSL:
```bash
ip addr show eth0 | grep 'inet ' | awk '{print $2}' | cut -d/ -f1
```

Or:
```bash
hostname -I | awk '{print $1}'
```

### 2. Set up Port Forwarding

Open **PowerShell as Administrator** on Windows:

```powershell
# Replace <WSL_IP> with the IP from step 1
netsh interface portproxy add v4tov4 address=0.0.0.0 port=3080 connectaddress=<WSL_IP> connectport=3080
```

Example:
```powershell
netsh interface portproxy add v4tov4 address=0.0.0.0 port=3080 connectaddress=172.20.10.2 connectport=3080
```

### 3. Configure Windows Firewall

```powershell
New-NetFirewallRule -DisplayName "WSL Port 3080" -Direction Inbound -LocalPort 3080 -Protocol TCP -Action Allow
```

### 4. Verify Configuration

Check port forwarding:
```powershell
netsh interface portproxy show all
```

Check firewall rule:
```powershell
Get-NetFirewallRule -DisplayName "WSL Port 3080"
```

### 5. Get Windows IP Address

On Windows PowerShell:
```powershell
ipconfig
```

Look for your main network adapter (Ethernet or Wi-Fi) IPv4 address.

Other machines can access: `http://<WINDOWS_IP>:3080`

## Quick Setup Script

Save and run this script in **PowerShell as Administrator**:

```powershell
# Get WSL IP automatically
$wslIp = (wsl hostname -I).Trim().Split()[0]
Write-Host "WSL IP: $wslIp"

# Add port forwarding
netsh interface portproxy add v4tov4 address=0.0.0.0 port=3080 connectaddress=$wslIp connectport=3080

# Add firewall rule
New-NetFirewallRule -DisplayName "WSL Port 3080" -Direction Inbound -LocalPort 3080 -Protocol TCP -Action Allow

Write-Host "Configuration complete!"
Write-Host "Other machines can access via http://<WINDOWS_IP>:3080"
```

## Cleanup

Remove port forwarding and firewall rule:

```powershell
# Remove port forwarding
netsh interface portproxy delete v4tov4 address=0.0.0.0 port=3080

# Remove firewall rule
Remove-NetFirewallRule -DisplayName "WSL Port 3080"
```

## Update After WSL Restart

WSL IP changes on restart. Update the configuration:

```powershell
# Delete old rule
netsh interface portproxy delete v4tov4 address=0.0.0.0 port=3080

# Get new WSL IP
$wslIp = (wsl hostname -I).Trim().Split()[0]

# Add new rule
netsh interface portproxy add v4tov4 address=0.0.0.0 port=3080 connectaddress=$wslIp connectport=3080
```

## Troubleshooting

### Check if port is listening

On Windows:
```powershell
netstat -ano | findstr :3080
```

### Check Windows Firewall status

```powershell
Get-NetFirewallProfile
```

### Test connection from another machine

```bash
# Linux/macOS
curl http://<WINDOWS_IP>:3080

# Or open in browser
```

## Different Ports

For different ports, replace `3080` with your desired port in all commands.

Example for port 3000:

```powershell
netsh interface portproxy add v4tov4 address=0.0.0.0 port=3000 connectaddress=<WSL_IP> connectport=3000
New-NetFirewallRule -DisplayName "WSL Port 3000" -Direction Inbound -LocalPort 3000 -Protocol TCP -Action Allow
```

## Multiple Ports

You can forward multiple ports by running the commands multiple times with different ports:

```powershell
# Port 3000
netsh interface portproxy add v4tov4 address=0.0.0.0 port=3000 connectaddress=<WSL_IP> connectport=3000
New-NetFirewallRule -DisplayName "WSL Port 3000" -Direction Inbound -LocalPort 3000 -Protocol TCP -Action Allow

# Port 8080
netsh interface portproxy add v4tov4 address=0.0.0.0 port=8080 connectaddress=<WSL_IP> connectport=8080
New-NetFirewallRule -DisplayName "WSL Port 8080" -Direction Inbound -LocalPort 8080 -Protocol TCP -Action Allow
```

## Security Notes

- Only opens ports on your local network
- Consider using HTTPS for sensitive data
- Close ports when not in use
- Review firewall rules regularly
