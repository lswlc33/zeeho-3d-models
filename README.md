# zeeho-3d-models

极核（Zeeho）车型的 3D 车模文件库。

## 内容

- `models/`：`.glb` 车模文件
- `index.json`：车模清单，客户端按清单读取列表和文件地址

## 添加车模

1. 把车模文件放进 `models/`，用车型命名；
2. 在 `index.json` 增加对应条目，填写文件名、字节数和版本号；
3. 单个文件必须小于 **20MB**（jsDelivr 对 GitHub 源文件的限制）；
4. 第三方模型请在 `note` 中注明作者、协议和原始链接。

提交推送后，jsDelivr 通常会在 15 分钟内刷新。
