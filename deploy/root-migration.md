# VPS: Minecraft を root で稼働させる手順

この手順は `minecraft.service` / `/opt/minecraft/server` の現行構成向け。VPSの作業は管理者がSSHで行い、Minecraftを停止してバックアップを取得してから実施する。**rootで動くPaperと全プラグインはホストの全権限を持つ。** これは `AccessDeniedException` の原因特定・解消を保証しない。

## 1. 停止と退避

```bash
sudo systemctl stop minecraft.service
sudo systemctl is-active minecraft.service  # inactive と表示されること
sudo tar -C /opt/minecraft -czpf "/root/minecraft-server-$(date +%Y%m%d-%H%M%S).tar.gz" server
```

バックアップコマンドが成功したことと、`/root/` のアーカイブが作成されたことを確認する。作業前にディスク容量も確認する。`Launcher.java` は起動時に `world/datapacks/` を削除して同期するため、稼働中のpullや同期は行わない。

## 2. develop を取得し、所有者を変更

```bash
cd /opt/minecraft/server
sudo git status --short
sudo git fetch origin --prune
sudo git switch develop
sudo git pull --ff-only origin develop
sudo chown -R root:root /opt/minecraft/server
sudo chmod 600 /opt/minecraft/server/.env  # .env が存在する場合のみ
```

`git status` に変更があれば原因を確認し、失われる操作は避ける。`git switch` / `pull` が失敗したら先へ進まない。`.env` やワールドデータは Git 管理外。所有者変更は `/opt/minecraft/server` 内の `.git` とワールド・プラグインも含む。Git操作も以後rootで実行し、`minecraft`ユーザーでのGit操作と混用しない。

## 3. systemd ユニットを反映

```bash
sudo cp -a /etc/systemd/system/minecraft.service "/root/minecraft.service.before-root-$(date +%Y%m%d-%H%M%S)"
sudo install -o root -g root -m 644 deploy/minecraft.service /etc/systemd/system/minecraft.service
sudo systemd-analyze verify /etc/systemd/system/minecraft.service
sudo systemctl daemon-reload
sudo systemctl cat minecraft.service
```

`User=root` / `Group=root` / `WorkingDirectory=/opt/minecraft/server` / `ExecStart=/usr/bin/java Launcher.java` を確認する。別途作成済みの `/etc/systemd/system/minecraft.service.d/` に上書き設定がある場合は `systemctl cat` を確認して競合を取り除く。実効ユーザーは `systemctl show minecraft.service -p User -p Group` で確認する。

## 4. 起動と検証

```bash
cd /opt/minecraft/server
sudo systemctl start minecraft.service
sudo systemctl status minecraft.service --no-pager -l
sudo systemctl show minecraft.service -p User -p Group -p MainPID
ps -eo pid,user:16,group:16,args | grep '[j]ava'
sudo journalctl -u minecraft.service -b --no-pager | grep -Ei 'AccessDeniedException|Failed to read chunk|Could not save|OutOfMemory|ERROR'
```

Launcher・Paperの両Javaプロセスがrootになっていることを確認する。空のgrep結果は**エラーが記録されていないこと**しか意味せず、以前失敗した資源ワールドのチャンクを読み直して正常に動作するかも確認する。特に `minecraft:resource` のチャンク `(87,-11)`、ファイル `world/dimensions/minecraft/resource/region/r.2.-1.mca` の読み込みを確認する。`chunk data will be lost` が出ていたため、問題のチャンクを再生成・上書きする前にバックアップを保管する。

## 5. 以後の更新・元に戻す場合

更新時は `sudo systemctl stop minecraft.service` → `cd /opt/minecraft/server` → `sudo git pull --ff-only origin develop` → `sudo systemctl start minecraft.service`。`.env` の内容やToken、ワールドはGitへpushしない。

元に戻す場合は停止し、退避した元のユニットを復元、`daemon-reload`、必要なファイルの所有者を元の `minecraft:minecraft` に戻してから起動する。ルート所有のデータを旧ユーザーで再利用するときにアクセス拒否が生じるため、所有者の復旧を省略しない。過去のバックアップから復元する場合は当日の更新差分が失われ得るため内容を比較する。
