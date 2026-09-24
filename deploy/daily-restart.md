# VPS: 日次再起動を設定する

Minecraftサーバーを毎日03:00（Asia/Tokyo）前後に再起動するための手順。02:55にメンテナンス処理を開始し、5分前通知、10秒カウントダウン、`save-all flush` の後に `minecraft.service` を再起動する。

## 1. RCON用Secretを設定する

`/opt/minecraft/server/.env` にRCON設定を追加する。パスワードは十分に長いランダム値を使用し、Gitへ登録しない。

```dotenv
RCON_HOST=127.0.0.1
RCON_PORT=25575
RCON_PASSWORD=実際のパスワード
```

既存の `DISCORD_BOT_TOKEN` は削除しない。

## 2. server.propertiesでRCONを有効にする

`/opt/minecraft/server/server.properties` の次の値を設定する。`rcon.password` は `.env` の `RCON_PASSWORD` と同じ値にする。

```properties
enable-rcon=true
rcon.port=25575
rcon.password=実際のパスワード
broadcast-rcon-to-ops=false
```

`server.properties` はGit管理外。RCONクライアントは `127.0.0.1` へ接続し、25575/TCPをインターネット向けに開放しない。ファイアウォールやConoHa側の許可設定にもRCONポートを追加しない。

設定変更後、Minecraftを一度再起動してRCONを有効化する。

## 3. RCON単体を確認する

```bash
cd /opt/minecraft/server
set -a
source .env
set +a
/usr/bin/python3 deploy/rcon.py "say RCON接続テスト"
```

ゲーム内にメッセージが表示され、コマンドがエラー終了しないことを確認する。

## 4. systemd unitを配置する

```bash
cd /opt/minecraft/server

sudo install -o root -g root -m 644   deploy/minecraft-maintenance.service   /etc/systemd/system/minecraft-maintenance.service

sudo install -o root -g root -m 644   deploy/minecraft-maintenance.timer   /etc/systemd/system/minecraft-maintenance.timer

sudo systemd-analyze verify   /etc/systemd/system/minecraft-maintenance.service   /etc/systemd/system/minecraft-maintenance.timer

sudo systemctl daemon-reload
sudo systemctl enable --now minecraft-maintenance.timer
```

## 5. timerを確認する

```bash
systemctl list-timers minecraft-maintenance.timer
systemctl status minecraft-maintenance.timer --no-pager -l
```

次回起動時刻が02:55（Asia/Tokyo）になっていることを確認する。

メンテナンス処理は約5分間動作するため、実際の `minecraft.service` 再起動は03:00直後になる。`save-all flush` の処理時間が加わるため、03:00:00ちょうどの再起動は保証しない。

## 6. ログを確認する

実行後は次の両方を確認する。

```bash
sudo journalctl -u minecraft-maintenance.service --since today --no-pager
sudo journalctl -u minecraft.service --since today --no-pager
```

Minecraftが02:55時点で停止している場合、メンテナンスサービスはMinecraftを起動せず、その日の処理を終了する。
