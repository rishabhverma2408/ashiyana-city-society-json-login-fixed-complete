# Oracle Cloud Always Free – Society App Setup

## Why this keeps your data
- App jar: `/opt/society-app/society-management.jar`
- Society data: `/var/lib/society-data/`  ← this folder is NOT overwritten when you update code

## Step A – Create free Oracle account
1. Open https://www.oracle.com/cloud/free/
2. Sign up (card may be required for verification; Always Free should not charge if you stay in free limits)
3. Sign in to Oracle Cloud Console

## Step B – Create Always Free VM
1. Menu → **Compute** → **Instances** → **Create Instance**
2. Name: `society-server`
3. Image: **Ubuntu 22.04**
4. Shape (pick one Always Free option):
   - Preferred: **VM.Standard.E2.1.Micro** (AMD) if available
   - Or: **VM.Standard.A1.Flex** with **1 OCPU** and **6 GB RAM**
5. Networking: use default VCN; assign a **public IP**
6. SSH keys: download the private key (`.key`) and keep it safe
7. Create the instance

If you see **Out of capacity**, try another Always Free region/shape or retry later.

## Step C – Open port 10000 on Oracle firewall
1. Instance details → click the **Subnet** / VCN link
2. **Security Lists** → Default Security List → **Add Ingress Rules**
3. Source: `0.0.0.0/0`
4. IP Protocol: TCP
5. Destination Port: `10000`
6. Save

## Step D – Connect from your Windows PC (PowerShell)
Replace values with yours:

```powershell
ssh -i "C:\path\to\your-private-key.key" ubuntu@YOUR_PUBLIC_IP
```

First time: type `yes` when asked.

## Step E – One-time server setup (on the VM)
```bash
# Upload project later; first install tools:
sudo apt-get update -y
sudo apt-get install -y openjdk-17-jdk maven git ufw
sudo mkdir -p /opt/society-app /var/lib/society-data
sudo chown -R ubuntu:ubuntu /opt/society-app /var/lib/society-data
sudo ufw allow OpenSSH
sudo ufw allow 10000/tcp
sudo ufw --force enable
```

## Step F – Put your project on the VM
### Option 1: Git (best)
```bash
cd ~
git clone YOUR_GITHUB_REPO_URL society-app
cd society-app
bash deploy/oracle/install-service.sh
```

### Option 2: From Windows with SCP
On your PC (PowerShell), from the project folder:
```powershell
scp -i "C:\path\to\your-private-key.key" -r . ubuntu@YOUR_PUBLIC_IP:~/society-app
```
Then on VM:
```bash
cd ~/society-app
bash deploy/oracle/install-service.sh
```

## Step G – Open the app
`http://YOUR_PUBLIC_IP:10000`

Login:
- Admin: `7007478334` / `123456`
- Secretary: `8796854510` / `123456`

## Later: update code without losing data
On the VM:
```bash
cd ~/society-app
git pull
bash deploy/oracle/update-app.sh
```
This replaces only the jar. `/var/lib/society-data` stays intact.

## Useful commands
```bash
sudo systemctl status society-app
sudo journalctl -u society-app -f
sudo systemctl restart society-app
```
