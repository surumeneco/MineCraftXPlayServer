# vanilla_world

Minecraft Java Edition 26.2 の標準オーバーワールド地形用ディメンション。

- ディメンションID: `xplay:vanilla_world`
- 地形ノイズ設定: `xplay:vanilla_overworld`（26.2標準 `minecraft:overworld` の完全な複製）
- ディメンションタイプ・バイオーム設定: 標準オーバーワールドを参照
- 前提: Lithosphere は `minecraft:overworld` の `noise_settings` だけを変更する。バニラの密度関数・ノイズパラメータ・バイオーム定義等が上書きされた場合は、地形同一性は保証されない。

`vanilla_overworld.json` は `scripts/sync_vanilla_world_noise.py` が Minecraft 26.2 の元データを取得し、Git blob SHA を検証した上で複製する。GitHub Actions は `develop` への追加時にこのファイルを生成・コミットする。26.2以外へ更新する場合は元データとパック形式を合わせて変更する。

本番では `develop` のコミット済みデータパック全体を反映してから、サーバーを停止・バックアップした状態で起動する。既存 `Launcher.java` が `datapacks/` を `world/datapacks/` に同期するため、後者への直接設置は不要。データパックによるディメンションの追加は新規ワールドとして扱い、既存 `resource/` の地形生成方式が自動変更されるわけではない。

サーバー起動後、`/datapack list enabled` と `/execute in xplay:vanilla_world run tp ~ ~ ~` の使用前にディメンション登録を確認する。Multiverse-Core 5 はデータパック由来ワールドを起動時に自動インポートする仕様だが、実環境での登録・地形生成・ネザー/エンド移動等は別途確認が必要。
