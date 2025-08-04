## 主要功能概述
- **房间系统**：创建/加入/离开房间
- **金币赛模式**：房主可开启金币赛模式，支持Vault经济系统的金币结算
- **统计系统**：记录玩家胜率、积分、金币收益等数据
- **排行榜功能**：查看服务器玩家积分排名

## 基础命令

### 主命令
`/ddz` 或 `/landlord` - 显示帮助菜单

### 子命令
| 命令 | 描述 | 用法示例 |
|------|------|----------|
| create | 创建房间 | `/ddz create [房间号]` |
| join | 加入房间 | `/ddz join <房间号>` |
| leave | 离开房间 | `/ddz leave` |
| ready | 准备/取消准备 | `/ddz ready` |
| list | 查看房间列表 | `/ddz list [页码]` |
| stats | 查看个人统计 | `/ddz stats` |
| top | 查看排行榜 | `/ddz top [数量]` |
| money | 开关金币赛（房主） | `/ddz money` |

## 配置选项
```yaml
turn-timeout: 60  # 出牌超时时间(秒)
scoreboard-enabled: true  # 是否启用计分板
bounty-enabled: true  # 是否启用金币赛
money-multiplier: 10  # 金币赛基础金额倍数
