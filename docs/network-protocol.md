# RTMap 网络协议草案(v1)

状态:草案。目标版本 Minecraft 1.20.1 / Fabric,使用 `fabric-networking-api-v1`(`PacketByteBuf` 自定义包)。

English version: [network-protocol.en.md](network-protocol.en.md)

## 1. 设计原则

- **客户端先开口**:只有客户端检测到服务端注册了本模组的通道,才发送 `client_hello`。服务端从不主动向未握手的玩家发包,所以原版客户端和未装本模组的客户端不受影响。
- **服务端是唯一权威**:权限由服务端决定,客户端传来的任何能力声明都只作协商参考,不当作授权依据。
- **可增量扩展**:协议整数版本只在不兼容修改时递增。新增功能用字符串 ID 声明,未知的功能 ID 双方一律忽略。
- **每次敏感请求都重新鉴权**:不因为握手时有权限就长期放行。
- **默认安全**:种子永远不放进握手包,必须由单独的请求获取。

## 2. 通道

命名空间 `rtmap`。

| 通道 | 方向 | 用途 |
|---|---|---|
| `rtmap:client_hello` | C2S | 客户端发起握手 |
| `rtmap:server_hello` | S2C | 服务端响应协商结果和权限 |
| `rtmap:permissions` | S2C | 权限变化时的通知(OP 变更、配置修改) |
| `rtmap:seed_request` | C2S | 请求世界种子 |
| `rtmap:seed_response` | S2C | 返回种子或拒绝原因 |
| `rtmap:chunk_query` | C2S | 询问一个矩形范围的区块:加载状态、数量,或两者都要 |
| `rtmap:chunk_data` | S2C | 返回这些数据或拒绝原因 |

目前的功能 ID:`seed`、`chunk_state`、`chunk_load`。后续功能(MSPT、真实区块耗时等)各自新增通道,并在 `features` 中声明。

## 3. 握手流程

```
Client                                   Server
  | JOIN 事件                              |
  |  canSend(client_hello)? 否 → 结束,按"无服务端"降级
  |--- client_hello(protocol, features) -->|
  |                                        | 协商版本、计算权限
  |<-- server_hello(protocol, world_id, ---|
  |        features, grants, status)       |
  | 保存协商结果                            |
```

`client_hello`:

| 字段 | 类型 | 说明 |
|---|---|---|
| protocol | VarInt | 客户端支持的最高协议版本 |
| min_protocol | VarInt | 客户端能接受的最低协议版本 |
| mod_version | String | 仅用于日志和排错,不参与判断 |
| features | List\<String\> | 客户端能使用的功能 ID |

`server_hello`:

| 字段 | 类型 | 说明 |
|---|---|---|
| status | Byte | 0 = OK,1 = 版本不兼容 |
| protocol | VarInt | 协商后实际使用的版本(= min(双方最高)) |
| world_id | UUID | 服务端持久化的世界标识,用于客户端缓存 |
| features | List\<String\> | 双方交集中服务端已启用的功能 |
| grants | List\<String\> | 当前玩家被授权的功能 ID(`features` 的子集) |

**版本协商**:`negotiated = min(client.protocol, server.protocol)`。若 `negotiated < max(client.min_protocol, server.min_protocol)`,返回 `status = 1`,客户端关闭所有网络功能,并给出一次性提示(聊天栏或 Toast,不弹窗,不踢人)。

**降级**:任何一步失败(没有通道、版本不兼容、包格式错误),客户端保持"纯客户端模式",不影响小地图、路径点、测量等本地功能。握手不会阻塞任何操作,所以没有超时:在收到 `server_hello` 之前,客户端只是暂时没有服务端数据。

## 4. 种子与权限

### 4.1 授权规则

```
allowed = isOp(player) || config.seed_sharing
```

- 单人游戏和自己开的局域网:客户端发现 `MinecraftClient.getServer() != null`,直接读内置服务端的种子,**不走网络协议**。
- 多人服务器:走 `seed_request`。
- `isOp` 判定:`player.hasPermissionLevel(2)`。若装了 `fabric-permissions-api`,则额外接受权限节点 `rtmap.seed`(软依赖,没装时不影响)。

### 4.2 `seed_request` / `seed_response`

`seed_request`:空包体。

`seed_response`:

| 字段 | 类型 | 说明 |
|---|---|---|
| status | Byte | 0 = OK,1 = 未授权,2 = 功能未启用,3 = 请求过于频繁 |
| seed | Long | 仅 `status = 0` 时存在 |

服务端处理要求:

1. 每次请求都重新计算 `allowed`。
2. 读取种子用主世界的 `getSeed()`(与维度无关,所有维度共用同一个世界种子)。
3. 每位玩家限频,例如 30 秒内最多 3 次,超过返回 `status = 3`。
4. 记录一条日志(玩家名、结果),方便管理员审计。

### 4.3 撤销与变更

`rtmap:permissions`(S2C):`grants`(同 `server_hello`)。

触发时机:
- `seed_sharing` 或 `chunk_data_sharing` 配置被命令或热重载修改。
- 玩家的 OP 状态变化。原版没有对应的 Fabric 事件,需要用 Mixin 挂 `PlayerManager` 的 `addToOperators` / `removeFromOperators`。

客户端收到后:如果 `chunk_state` 或 `chunk_load` 不再在 `grants` 里,停止请求并隐藏区块图层。如果 `seed` 不再在 `grants` 里,**立即停用并清除内存中的种子**,不再用于计算史莱姆区块。已写入磁盘缓存的种子也一并删除。

> 局限:无法阻止玩家自己记录种子或修改客户端。撤销只保证本模组正常行为下不再使用。文档里要写明。

### 4.4 客户端缓存

- **服务端下发的种子只保存在内存里,不写入磁盘。**种子只有在服务端仍然授权时才可用,磁盘副本除了能在撤销授权之后继续存活以外没有任何用处。下次进入服务器时客户端重新请求即可(受限频约束)。
- 用 `world_id`(服务端下发的 UUID)标识世界,不使用服务器地址。这样同一地址的多个世界、换端口、换域名都不会串数据。
- 手动输入的种子按世界存到磁盘,放在该世界的地图旁边(`seed.json`);若服务端没装本模组,没有 `world_id`,则按"服务器地址 + 端口"识别世界。
- 授权被撤销时,**只清除来自服务端下发的种子**,手动输入的保留。

## 5. 区块加载与负载数据

两个功能共用一对请求/响应:`chunk_state`(每个区块如何被加载、被什么占着)和 `chunk_load`(每个区块的实体、方块实体、漏斗数量)。

### 5.1 授权规则

```
allowed = isOp(player) || config.chunk_data_sharing
```

- 单人游戏和自己开的局域网:客户端直接读内置服务端,**不走网络协议**。
- 多人服务器:两个功能和种子一样通过 `grants` 下发,规则相同,每次查询都重新检查。`chunk_data_sharing` 默认 `false`。

### 5.2 `chunk_query`(C2S)

无状态:每次查询单独回答,服务端在两次查询之间除了限频计数以外不保存任何东西。

| 字段 | 类型 | 说明 |
|---|---|---|
| dimension | String(最长 128) | 必须等于玩家当前所在维度的 ID,防止查询和切换维度撞在一起 |
| min_chunk_x | Int | 矩形西边缘,以区块计 |
| min_chunk_z | Int | 矩形北边缘,以区块计 |
| width | VarInt | 1 到 256 |
| height | VarInt | 1 到 256;`width * height` 不超过 65536 |
| what | Byte | 第 0 位 = 加载状态(`chunk_state`),第 1 位 = 数量(`chunk_load`) |

### 5.3 `chunk_data`(S2C)

| 字段 | 类型 | 说明 |
|---|---|---|
| status | Byte | 0 = OK,1 = 未授权,2 = 请求过于频繁,3 = 请求不合法,4 = 维度不符 |

`status = 0` 时后面跟一份快照:

| 字段 | 类型 | 说明 |
|---|---|---|
| dimension | String | 这份数据对应的维度;客户端发现已不是当前维度就丢弃 |
| min_chunk_x, min_chunk_z | Int, Int | 与请求一致 |
| width, height | VarInt, VarInt | 与请求一致 |
| have | Byte | 第 0 位 = 含 `state`,第 1 位 = 含 `load` |
| state | 字节数组 | `width * height` 字节,从 `min_chunk_z` 起逐行、每行从西到东。`have` 第 0 位置位时才有 |
| load | 见下 | `have` 第 1 位置位时才有 |

状态字节:第 0 到 1 位是加载等级(0 = 未加载,1 = 边缘,2 = 方块加载,3 = 实体加载);第 2 到 7 位是占着这个区块的加载票种类:玩家、传送门、强制加载、出生点、末影龙、其他。

`load`:一个 VarInt 表示非空区块的个数,之后每个非空区块依次是:VarInt 索引间隔(它的索引减去上一个索引再减 1,从 -1 开始算)、VarInt 实体数、VarInt 方块实体数、VarInt 漏斗数。数量截断到 65535。

服务端处理要求:

1. 只在服务端线程里读取,而且只读。用到两个访问器 Mixin:`ThreadedAnvilChunkStorage.getChunkHolder` 和 `ChunkTicketManager.ticketsByPosition`。
2. 限频:每位玩家 5 秒内最多 10 次查询。这两个数字目前写死在代码里,还不是设置项。
3. 读取任何东西之前先校验:包体大小、`what` 的位、尺寸上限、坐标范围(距原点不超过 1,900,000 个区块)、已协商的功能、权限和维度。
4. 处在实体加载等级、但区块不在任何玩家的模拟距离内(`ChunkTicketManager.shouldTickEntities` 为假)的持有者,按方块加载上报。

客户端行为:请求可见区域再向外 3 个区块,最多每 1.5 秒一次,两次之间至少隔 0.6 秒;收到“请求过于频繁”或“未授权”后退避 5 秒;丢弃对应其他维度的回答。客户端接受最大 1 MiB 的 `chunk_data` 包。

这两个通道目前还没有自己的版本字节,所以对它们做不兼容修改需要抬协议版本。

## 6. 服务端配置与命令

`config/rtmap-server.json`:

```json
{
  "seed_sharing": false,
  "chunk_data_sharing": false,
  "command_permission_level": 3,
  "seed_request_limit": 3,
  "seed_request_window_seconds": 30
}
```

| 键 | 默认值 | 允许范围 |
|---|---|---|
| `seed_sharing` | `false` | 布尔值 |
| `chunk_data_sharing` | `false` | 布尔值 |
| `command_permission_level` | 3 | 2 到 4 |
| `seed_request_limit` | 3 | 1 到 60 |
| `seed_request_window_seconds` | 30 | 10 到 3600 |

命令(所需权限等级由 `command_permission_level` 决定):

| 命令 | 作用 |
|---|---|
| `/rtmap seed_sharing` | 显示当前值 |
| `/rtmap seed_sharing <true\|false>` | 修改并保存开关,同时向在线玩家发送 `permissions` |
| `/rtmap chunk_data_sharing` | 显示当前值 |
| `/rtmap chunk_data_sharing <true\|false>` | 修改并保存开关,同时向在线玩家发送 `permissions` |
| `/rtmap reload` | 重载配置文件,发送 `permissions`,并刷新玩家的命令树 |

### 防篡改

管理员可以调整这些值,下面几点保证其他人改不了:

- **只在服务端。**数值只从服务端的配置目录读取,没有任何网络包能读写它们,客户端无法影响。
- **状态在服务端。**限频计数保存在服务端内存里,按玩家 UUID 计,客户端发送的任何内容都不会影响计数。
- **硬性上下限。**加载时越界的值会被截断。特别是 `command_permission_level` 永远不会低于 2,所以配置写错也不会让普通玩家能使用 `/rtmap`。
- **失败时保持关闭。**配置文件无法解析时,沿用上一次有效的设置(首次加载则用默认值,此时 `seed_sharing` 为关)。类型错误的值回退为默认值。
- **安全写入。**保存时先写临时文件,再原子替换正式文件,崩溃也不会留下写了一半的文件。

不在保护范围内:能访问服务端文件或控制台的人就是管理员,可以修改配置。这是设计如此,代码层面无法阻止。

`world_id` 保存在主世界的 `PersistentState` 里,首次加载时随机生成。复制世界存档到新服务器后,`world_id` 会跟着存档走。需要区分副本时,管理员可以删除该状态重新生成。

## 7. 其他通用要求

- **包大小**:所有包体设上限(例如 4 KB),超出直接丢弃并记录。唯一的例外是 `chunk_data`,客户端最大接受 1 MiB,并且其中每个尺寸都按查询的上限检查。
- **字符串和列表**:限制长度和元素数(例如 `features` 最多 64 项,每项最长 64 字符),防止滥用。
- **线程**:收包回调在网络线程,涉及游戏状态必须切回主线程(`server.execute` / `client.execute`)。
- **不信任客户端**:客户端的 `features` 只是声明,服务端据此决定要不要发数据,但是否授权只看自己的权限判断。
- **日志级别**:握手结果用 INFO,权限拒绝用 DEBUG,协议错误用 WARN。

## 8. 后续功能的接入方式

新增一个功能(例如 `mspt`)需要:
1. 在 `features` 中增加字符串 ID,在服务端配置里加开关和权限判定。
2. 新增专用通道,包体自带"版本字节",以后只改这个功能时不用抬协议版本。
3. 在 `grants` 里下发授权,变化时走 `permissions` 通道通知。
4. 功能不可用时,客户端对应的图层会被隐藏(工具栏和设置里都不出现);服务端拒绝查询时,全屏地图会说明原因。

## 9. 已确定

- 命令权限等级读配置(截断到 2 到 4,默认 3)。
- 限频参数开放给管理员配置,但有硬性上下限(见第 6 节)。
- Servux 兼容层(结构数据)放在这个协议之外,独立实现,不占用 `rtmap:` 通道。

## 10. 尚未实现

- 区块查询的上限可配置(目前尺寸和频率写死在代码里),以及区块通道的版本字节。
- 真实区块耗时和 MSPT 的通道。
- 与 `fabric-permissions-api` 的软集成(权限节点 `rtmap.seed`)。
- 包级别的自动化测试。`chunk_query` / `chunk_data` 在带权限的专用服务端上的交互还没有测试过。
