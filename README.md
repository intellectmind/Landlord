## Main Features Overview
- **Room System**: Create/Join/Leave rooms
- **Gold Coin Mode**: Room owner can enable Gold Coin Mode, supporting gold coin settlements via the Vault economy system
- **Statistics System**: Records player win rates, points, gold earnings, and other stats
- **Leaderboard Function**: View server-wide player point rankings

> Currently only supports zh_cn

## Basic Commands

### Main Command
`/ddz` or `/landlord` - Displays the help menu

### Subcommands
| Command | Description | Usage Example |
|---------|-------------|---------------|
| create  | Create a room | `/ddz create [room ID]` |
| join    | Join a room | `/ddz join <room ID>` |
| leave   | Leave a room | `/ddz leave` |
| ready   | Ready/Unready | `/ddz ready` |
| list    | View room list | `/ddz list [page number]` |
| stats   | View personal stats | `/ddz stats` |
| top     | View leaderboard | `/ddz top [number]` |
| money   | Toggle Gold Coin Mode (room owner only) | `/ddz money` |

## Configuration Options
```yaml
turn-timeout: 60       # Turn timeout (seconds)
scoreboard-enabled: true  # Whether to enable scoreboard
bounty-enabled: true    # Whether to enable Gold Coin Mode
money-multiplier: 50    # Gold Coin Mode base amount multiplier
