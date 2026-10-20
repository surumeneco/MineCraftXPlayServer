# VPS: 日次再起動を設定する

Minecraftサーバーを毎日03:00（Asia/Tokyo）前後に再起動するための手順。02:55にメンテナンス処理を開始し、5分前通知、10秒カウントダウン、`save-all flush` の後に `minecraft.service` を再起動する。

RCONの応答待ち時間は通常の`/say`が5秒、`save-all flush`が120秒。`deploy/rcon.py`は環境変数`RCON_TIMEOUT_SECONDS`（秒、正の有限数）でコマンド単位のタイムアウトを受け取る。保存コマンドが120秒以内に正常応答しない場合、メンテナンス処理は失敗終了し、確認できない保存状態のまま再起動しない。再起動予定は日次のまま維持する。

## 1. RCON用Secretを設定する

`/opt/minecraft/server/.env` にRCON設定を追加する。パスワードは英数字で十分に長いランダム値を使用し、Gitへ登録しない。

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

sudo install -o root -g root -m 644 deploy/minecraft-maintenance.service /etc/systemd/system/minecraft-maintenance.service
sudo install -o root -g root -m 644 deploy/minecraft-maintenance.timer /etc/systemd/system/minecraft-maintenance.timer
sudo systemd-analyze verify /etc/systemd/system/minecraft-maintenance.service /etc/systemd/system/minecraft-maintenance.timer

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

## WWC/WWW ワールド別Genomeレコード移行（導入後のみ有効化）

WCL/WWC 2.1.0/WWW 1.1.0の実機確認完了後に限り、`/opt/minecraft/server/.env`へ
`WONDERFUL_MIGRATION_ENABLED=1` を追加する（**既定0、無効**）。
対応版が未導入の場合は変更しない。サーバー起動中かつカウントダウン終了後、
`wwc storage migrate` → `wwc storage status` → `www storage migrate` →
`www storage status` → `save-all flush` → 再起動、の順で実行する。

移行コマンドとstatusはRCON/consoleへ次の形式の**単一行・装飾なし**テキストを返すこと。
`WCL-MIGRATION job=<id> status=RUNNING|SUCCESS|FAILED processed=<n> copied=<n> skipped=<n> failed=<n>`
`job`は呼出しごとに新規IDとし、後続statusと一致させる。startで完了済みでもstatusの照合を省略しない。
RCONプロセスのexit 0だけでは成功扱いにしない。コマンド/応答が存在しない、job不一致、
失敗または時間超過では**save-all flushと再起動を実行しない**。

任意設定：`WONDERFUL_MIGRATION_MAX_POLLS=300`（1～300）、
`WONDERFUL_MIGRATION_POLL_SECONDS=2`（0～9）。300×2秒×2種別で最長約20分（RCONや保存時間を除く）。
通知待機5分も含めたsystemdの`TimeoutStartSec=30min`で制限し、未完了なら安全側で再起動を中止する。
ロールバック時は`WONDERFUL_MIGRATION_ENABLED=0`に戻す。
現在ロード済みのワールド内のWGPFレコードはファイル単位でworldへミラーするため、ロードされていない個体やオフラインのアイテムからの復元参照も保全する。
ただし、まだロードされていない別ワールド/外部アーカイブ、すでに失われた元データは対象外。
旧保存ファイルは移行後も削除せず、regenの前に別領域へのバックアップを確保する。
