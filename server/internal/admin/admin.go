// Package admin implements the server operator's command line: looking at
// workspaces and members, and recovering a workspace whose leader lost
// their phone. It talks to the store directly, so it works while the
// server is running (SQLite allows several processes).
package admin

import (
	"context"
	"errors"
	"fmt"
	"io"
	"os"
	"strings"
	"text/tabwriter"
	"time"

	"github.com/GeoCrew-Dev/GeoCrew-Server/internal/blob"
	"github.com/GeoCrew-Dev/GeoCrew-Server/internal/export"
	"github.com/GeoCrew-Dev/GeoCrew-Server/internal/store"
	"github.com/GeoCrew-Dev/GeoCrew-Server/internal/store/sqldb"
)

// Usage is printed for "admin help" and for unknown commands.
const Usage = `用法：geocrew-server [参数] admin <命令>

  workspaces                       列出所有工作组
  members <工作组>                  列出成员（含已移出的）
  invite <工作组>                   更换邀请码并显示新码
  role <工作组> <成员> <角色>        修改角色：leader / member / readonly
  revoke <工作组> <成员>            移出成员，令牌立即失效
  backup <目标文件>                 在线备份数据库（附件目录另行复制）
  copy <目标数据库>                  把全部数据复制到另一个空库：postgres://… 或 .db 文件，
                                   用于从 SQLite 换到 PostgreSQL（或反过来）；附件目录不变
  export <工作组> <目标文件>         导出当前全部记录：.geojson 结尾为 GeoJSON（可直接用 QGIS 打开），
                                   .zip 结尾为工程包（含照片和记录表单，安卓端可导入为新工程），
                                   .csv 结尾为样品清单（每件样品一行，时间按本机时区），
                                   .sections.csv 结尾为实测剖面分层表（每层一行，含真厚度），
                                   .boreholes.csv 结尾为钻孔分层表（每层一行，含孔口坐标和水位），
                                   .structures.csv 结尾为产状表（面状、线状产状各一行，供赤平投影软件），
                                   .workload.csv 结尾为工作量统计（每天每人的记录、样品、照片、轨迹，按本机时区），
                                   .routes.csv 结尾为地质路线记录表（按点序每点一行，含点间描述），
                                   .kml 结尾为 KML（奥维、Google Earth 可打开，按共享图例着色），
                                   .gpx 结尾为 GPX（点、线和带逐点时间的轨迹，导入 GPS 和户外软件），
                                   .gpkg 结尾为 GeoPackage（QGIS、ArcGIS 可打开，点线面分表）

  files <工作组> [prune]             统计附件：占用空间、被记录引用的、没有任何记录引用的、缺失的；
                                   加 prune 删除没有引用且上传超过 1 天的附件（历史版本引用的不删）
  delete <工作组> [confirm]          永久删除整个工作组（成员、令牌、记录和历史、讨论、附件）；
                                   不加 confirm 只列出将删除的内容；不可恢复，删除前先 backup

<工作组> 和 <成员> 可以写 ID 或其开头几位，只要能唯一确定。
`

// Options are what some commands need beyond the database.
type Options struct {
	// Files holds attachments; without it packages are exported without photos.
	Files *blob.Disk
	// OpenDatabase opens a copy target named on the command line (a
	// PostgreSQL URL or an SQLite file); nil disables "copy".
	OpenDatabase func(target string) (*sqldb.DB, error)
}

// Run executes one admin command against st.
func Run(ctx context.Context, st store.Store, args []string, out io.Writer) error {
	return RunWith(ctx, st, Options{}, args, out)
}

// RunWith executes one admin command against st with the given options.
func RunWith(ctx context.Context, st store.Store, opt Options, args []string, out io.Writer) error {
	if len(args) == 0 || args[0] == "help" || args[0] == "-h" {
		fmt.Fprint(out, Usage)
		return nil
	}
	cmd, rest := args[0], args[1:]
	need := func(n int) error {
		if len(rest) != n {
			return fmt.Errorf("%s 需要 %d 个参数\n\n%s", cmd, n, Usage)
		}
		return nil
	}
	switch cmd {
	case "workspaces":
		if err := need(0); err != nil {
			return err
		}
		return listWorkspaces(ctx, st, out)
	case "members":
		if err := need(1); err != nil {
			return err
		}
		ws, err := findWorkspace(ctx, st, rest[0])
		if err != nil {
			return err
		}
		return listMembers(ctx, st, ws, out)
	case "invite":
		if err := need(1); err != nil {
			return err
		}
		ws, err := findWorkspace(ctx, st, rest[0])
		if err != nil {
			return err
		}
		code, err := st.ResetInvite(ctx, ws.ID)
		if err != nil {
			return err
		}
		fmt.Fprintf(out, "「%s」的新邀请码：%s （旧码已失效）\n", ws.Name, code)
		return nil
	case "role":
		if err := need(3); err != nil {
			return err
		}
		role := store.Role(rest[2])
		if !role.Valid() {
			return fmt.Errorf("角色只能是 leader、member 或 readonly")
		}
		ws, m, err := findMember(ctx, st, rest[0], rest[1])
		if err != nil {
			return err
		}
		if err := st.SetRole(ctx, ws.ID, m.ID, role); err != nil {
			return explain(err)
		}
		fmt.Fprintf(out, "已将「%s」的 %s 设为 %s\n", ws.Name, m.Nickname, role)
		return nil
	case "revoke":
		if err := need(2); err != nil {
			return err
		}
		ws, m, err := findMember(ctx, st, rest[0], rest[1])
		if err != nil {
			return err
		}
		if err := st.RevokeMember(ctx, ws.ID, m.ID); err != nil {
			return explain(err)
		}
		fmt.Fprintf(out, "已将 %s 移出「%s」。已连接的实时通道会在下次重连时断开。\n", m.Nickname, ws.Name)
		return nil
	case "copy":
		if err := need(1); err != nil {
			return err
		}
		return copyDatabase(ctx, st, opt, rest[0], out)
	case "backup":
		if err := need(1); err != nil {
			return err
		}
		b, ok := st.(store.Backuper)
		if !ok {
			return errors.New("当前存储不支持在线备份")
		}
		if _, err := os.Stat(rest[0]); err == nil {
			return fmt.Errorf("%s 已存在，不会覆盖", rest[0])
		}
		if err := b.Backup(ctx, rest[0]); err != nil {
			return err
		}
		fmt.Fprintf(out, "数据库已备份到 %s，请另行复制附件目录 files/\n", rest[0])
		return nil
	case "export":
		if err := need(2); err != nil {
			return err
		}
		ws, err := findWorkspace(ctx, st, rest[0])
		if err != nil {
			return err
		}
		f, err := os.OpenFile(rest[1], os.O_WRONLY|os.O_CREATE|os.O_EXCL, 0o644)
		if err != nil {
			if errors.Is(err, os.ErrExist) {
				return fmt.Errorf("%s 已存在，不会覆盖", rest[1])
			}
			return err
		}
		var msg string
		lower := strings.ToLower(rest[1])
		if strings.HasSuffix(lower, ".gpkg") {
			// SQLite writes the file itself; the empty one made above reserves the name.
			f.Close()
			var n int
			n, err = export.GeoPackage(ctx, st, ws.ID, rest[1])
			msg = fmt.Sprintf("已把「%s」的 %d 条记录导出到 %s", ws.Name, n, rest[1])
			f = nil
		} else if strings.HasSuffix(lower, ".kml") {
			var n int
			n, err = export.KML(ctx, st, ws.ID, f)
			msg = fmt.Sprintf("已把「%s」的 %d 条记录导出到 %s", ws.Name, n, rest[1])
		} else if strings.HasSuffix(lower, ".gpx") {
			var n int
			n, err = export.GPX(ctx, st, ws.ID, f)
			msg = fmt.Sprintf("已把「%s」的 %d 条记录导出到 %s", ws.Name, n, rest[1])
		} else if strings.HasSuffix(lower, ".routes.csv") {
			var n int
			n, err = export.Routes(ctx, st, ws.ID, f)
			msg = fmt.Sprintf("已把「%s」地质路线的 %d 行导出到 %s", ws.Name, n, rest[1])
		} else if strings.HasSuffix(lower, ".workload.csv") {
			var n int
			n, err = export.Workload(ctx, st, ws.ID, time.Local, f)
			msg = fmt.Sprintf("已把「%s」的工作量统计（%d 行）导出到 %s", ws.Name, n, rest[1])
		} else if strings.HasSuffix(lower, ".structures.csv") {
			var n int
			n, err = export.Structures(ctx, st, ws.ID, f)
			msg = fmt.Sprintf("已把「%s」的 %d 条产状导出到 %s", ws.Name, n, rest[1])
		} else if strings.HasSuffix(lower, ".boreholes.csv") {
			var n int
			n, err = export.Boreholes(ctx, st, ws.ID, f)
			msg = fmt.Sprintf("已把「%s」钻孔的 %d 个分层导出到 %s", ws.Name, n, rest[1])
		} else if strings.HasSuffix(lower, ".sections.csv") {
			var n int
			n, err = export.Sections(ctx, st, ws.ID, f)
			msg = fmt.Sprintf("已把「%s」实测剖面的 %d 个分层导出到 %s", ws.Name, n, rest[1])
		} else if strings.HasSuffix(lower, ".csv") {
			var n int
			n, err = export.Samples(ctx, st, ws.ID, time.Local, f)
			msg = fmt.Sprintf("已把「%s」的 %d 件样品导出到 %s", ws.Name, n, rest[1])
		} else if strings.HasSuffix(lower, ".zip") {
			open := func(sha string) (io.ReadCloser, error) {
				if opt.Files == nil {
					return nil, os.ErrNotExist
				}
				return opt.Files.Open(ws.ID, sha)
			}
			var sum export.PackageSummary
			sum, err = export.Package(ctx, st, ws.ID, open, "geocrew-server admin", time.Now(), f)
			msg = fmt.Sprintf("已把「%s」的 %d 条记录、%d 张照片、%d 段录音打包到 %s", ws.Name, sum.Features, sum.Photos, sum.Audio, rest[1])
			if sum.Missing > 0 {
				msg += fmt.Sprintf("（%d 张照片服务器上没有，已跳过）", sum.Missing)
			}
		} else {
			var n int
			n, err = export.GeoJSON(ctx, st, ws.ID, f)
			msg = fmt.Sprintf("已把「%s」的 %d 条记录导出到 %s", ws.Name, n, rest[1])
		}
		if f != nil {
			if cerr := f.Close(); err == nil {
				err = cerr
			}
		}
		if err != nil {
			os.Remove(rest[1])
			return err
		}
		fmt.Fprintln(out, msg)
		return nil
	case "files":
		if len(rest) < 1 || len(rest) > 2 || (len(rest) == 2 && rest[1] != "prune") {
			return fmt.Errorf("用法：files <工作组> [prune]\n\n%s", Usage)
		}
		if opt.Files == nil {
			return errors.New("没有配置附件目录")
		}
		ws, err := findWorkspace(ctx, st, rest[0])
		if err != nil {
			return err
		}
		return files(ctx, st, opt.Files, ws, len(rest) == 2, time.Now(), out)
	case "delete":
		if len(rest) < 1 || len(rest) > 2 || (len(rest) == 2 && rest[1] != "confirm") {
			return fmt.Errorf("用法：delete <工作组> [confirm]\n\n%s", Usage)
		}
		ws, err := findWorkspace(ctx, st, rest[0])
		if err != nil {
			return err
		}
		return deleteWorkspace(ctx, st, opt.Files, ws, len(rest) == 2, out)
	default:
		return fmt.Errorf("未知命令 %q\n\n%s", cmd, Usage)
	}
}

func listWorkspaces(ctx context.Context, st store.Store, out io.Writer) error {
	all, err := st.Workspaces(ctx)
	if err != nil {
		return err
	}
	if len(all) == 0 {
		fmt.Fprintln(out, "还没有工作组")
		return nil
	}
	tw := tabwriter.NewWriter(out, 0, 4, 2, ' ', 0)
	fmt.Fprintln(tw, "ID\t名称\t成员\t组长\t创建时间")
	for _, ws := range all {
		ms, err := st.Members(ctx, ws.ID)
		if err != nil {
			return err
		}
		active, leaders := 0, []string{}
		for _, m := range ms {
			if m.Revoked {
				continue
			}
			active++
			if m.Role == store.RoleLeader {
				leaders = append(leaders, m.Nickname)
			}
		}
		fmt.Fprintf(tw, "%s\t%s\t%d\t%s\t%s\n", ws.ID, ws.Name, active, strings.Join(leaders, "、"), ws.CreatedAt.Local().Format(time.DateTime))
	}
	return tw.Flush()
}

func listMembers(ctx context.Context, st store.Store, ws store.Workspace, out io.Writer) error {
	ms, err := st.Members(ctx, ws.ID)
	if err != nil {
		return err
	}
	fmt.Fprintf(out, "「%s」 邀请码 %s\n", ws.Name, ws.InviteCode)
	tw := tabwriter.NewWriter(out, 0, 4, 2, ' ', 0)
	fmt.Fprintln(tw, "ID\t昵称\t角色\t加入时间\t最后活动\t状态")
	for _, m := range ms {
		state := ""
		if m.Revoked {
			state = "已移出"
		}
		seen := "-"
		if m.LastSeen != nil {
			seen = m.LastSeen.Local().Format("2006-01-02 15:04")
		}
		fmt.Fprintf(tw, "%s\t%s\t%s\t%s\t%s\t%s\n", m.ID, m.Nickname, m.Role, m.JoinedAt.Local().Format(time.DateTime), seen, state)
	}
	return tw.Flush()
}

func findWorkspace(ctx context.Context, st store.Store, ref string) (store.Workspace, error) {
	all, err := st.Workspaces(ctx)
	if err != nil {
		return store.Workspace{}, err
	}
	var hit []store.Workspace
	for _, ws := range all {
		if ws.ID == ref {
			return ws, nil
		}
		if strings.HasPrefix(ws.ID, ref) {
			hit = append(hit, ws)
		}
	}
	switch len(hit) {
	case 1:
		return hit[0], nil
	case 0:
		return store.Workspace{}, fmt.Errorf("找不到工作组 %q", ref)
	default:
		return store.Workspace{}, fmt.Errorf("%q 对应多个工作组，请多写几位", ref)
	}
}

func findMember(ctx context.Context, st store.Store, wsRef, memberRef string) (store.Workspace, store.Member, error) {
	ws, err := findWorkspace(ctx, st, wsRef)
	if err != nil {
		return ws, store.Member{}, err
	}
	ms, err := st.Members(ctx, ws.ID)
	if err != nil {
		return ws, store.Member{}, err
	}
	var hit []store.Member
	for _, m := range ms {
		if m.Revoked {
			continue
		}
		if m.ID == memberRef {
			return ws, m, nil
		}
		if strings.HasPrefix(m.ID, memberRef) {
			hit = append(hit, m)
		}
	}
	switch len(hit) {
	case 1:
		return ws, hit[0], nil
	case 0:
		return ws, store.Member{}, fmt.Errorf("「%s」里没有成员 %q", ws.Name, memberRef)
	default:
		return ws, store.Member{}, fmt.Errorf("%q 对应多个成员，请多写几位", memberRef)
	}
}

func explain(err error) error {
	if errors.Is(err, store.ErrLastLeader) {
		return errors.New("工作组至少要保留一名组长，请先把别人设为组长")
	}
	return err
}

// pruneAfter keeps fresh uploads: the app uploads a photo before the
// record that lists it, so a new file is briefly unreferenced.
const pruneAfter = 24 * time.Hour

func files(ctx context.Context, st store.Store, disk *blob.Disk, ws store.Workspace, prune bool, now time.Time, out io.Writer) error {
	stored, err := disk.List(ws.ID)
	if err != nil {
		return err
	}
	refs, err := export.Referenced(ctx, st, ws.ID)
	if err != nil {
		return err
	}
	var total, orphanBytes, removedBytes int64
	var orphans, removed int
	onDisk := make(map[string]bool, len(stored))
	for _, f := range stored {
		onDisk[f.SHA] = true
		total += f.Size
		if refs[f.SHA] {
			continue
		}
		orphans++
		orphanBytes += f.Size
		if prune && now.Sub(f.ModTime) > pruneAfter {
			if err := disk.Remove(ws.ID, f.SHA); err != nil {
				return err
			}
			removed++
			removedBytes += f.Size
		}
	}
	missing := 0
	for sha := range refs {
		if !onDisk[sha] {
			missing++
		}
	}
	fmt.Fprintf(out, "「%s」的附件：%d 个，共 %s\n", ws.Name, len(stored), size(total))
	fmt.Fprintf(out, "  被记录引用：%d 个（含历史版本和共享叠加图）\n", len(refs)-missing)
	fmt.Fprintf(out, "  没有记录引用：%d 个，共 %s\n", orphans, size(orphanBytes))
	if missing > 0 {
		fmt.Fprintf(out, "  记录引用了但服务器上没有：%d 个（组员可能还没上传）\n", missing)
	}
	if prune {
		fmt.Fprintf(out, "已删除 %d 个，释放 %s；上传不满 1 天的保留\n", removed, size(removedBytes))
	}
	return nil
}

// deleteWorkspace shows what a workspace holds and, when confirmed,
// removes it from the database and then its attachment directory.
func deleteWorkspace(ctx context.Context, st store.Store, disk *blob.Disk, ws store.Workspace, confirmed bool, out io.Writer) error {
	ms, err := st.Members(ctx, ws.ID)
	if err != nil {
		return err
	}
	active := 0
	for _, m := range ms {
		if !m.Revoked {
			active++
		}
	}
	records := 0
	for since := int64(0); ; {
		fs, more, err := st.PullFeatures(ctx, ws.ID, since, 1000)
		if err != nil {
			return err
		}
		for _, f := range fs {
			if !f.Deleted {
				records++
			}
			since = f.Seq
		}
		if !more {
			break
		}
	}
	var files int
	var bytes int64
	if disk != nil {
		stored, err := disk.List(ws.ID)
		if err != nil {
			return err
		}
		for _, f := range stored {
			files++
			bytes += f.Size
		}
	}
	fmt.Fprintf(out, "「%s」（%s，创建于 %s）：%d 位成员，%d 条记录，%d 个附件 %s\n",
		ws.Name, ws.ID, ws.CreatedAt.Local().Format(time.DateTime), active, records, files, size(bytes))
	if !confirmed {
		fmt.Fprintf(out, "以上内容将被永久删除，不可恢复。确认无误（并已 backup）后执行：\n  admin delete %s confirm\n", ws.ID)
		return nil
	}
	if err := st.DeleteWorkspace(ctx, ws.ID); err != nil {
		return explain(err)
	}
	if disk != nil {
		if err := disk.RemoveWorkspace(ws.ID); err != nil {
			return fmt.Errorf("数据库里已删除，但附件目录没删干净：%w", err)
		}
	}
	fmt.Fprintf(out, "已删除。组员手机上的数据不受影响。定时备份的数据库快照里仍有它，按保留份数逐渐淘汰；备份目录里的附件不会自动删，需要时手动删除 files/%s。\n", ws.ID)
	return nil
}

func size(n int64) string {
	switch {
	case n >= 1<<30:
		return fmt.Sprintf("%.1f GB", float64(n)/(1<<30))
	case n >= 1<<20:
		return fmt.Sprintf("%.1f MB", float64(n)/(1<<20))
	case n >= 1<<10:
		return fmt.Sprintf("%.1f KB", float64(n)/(1<<10))
	default:
		return fmt.Sprintf("%d B", n)
	}
}

func copyDatabase(ctx context.Context, st store.Store, opt Options, target string, out io.Writer) error {
	src, ok := st.(*sqldb.DB)
	if !ok || opt.OpenDatabase == nil {
		return errors.New("当前存储不支持复制")
	}
	dst, err := opt.OpenDatabase(target)
	if err != nil {
		return err
	}
	defer dst.Close()
	if src.Dialect() == dst.Dialect() && src.Dialect() == "sqlite" {
		fmt.Fprintln(out, "提示：SQLite 之间的复制可以直接用 backup。")
	}
	counts, err := src.CopyTo(ctx, dst)
	if err != nil {
		return err
	}
	fmt.Fprintf(out, "已复制到 %s：工作组 %d，成员 %d，记录 %d（历史版本 %d），讨论 %d，附件登记 %d。\n",
		dst.Dialect(), counts["workspaces"], counts["members"], counts["features"], counts["feature_history"],
		counts["comments"], counts["files"])
	if dst.Dialect() == "sqlite" {
		fmt.Fprintln(out, "把这个文件改名为 geocrew.db 放进数据目录，去掉 -database 启动即可；附件仍在数据目录的 files/ 下。")
	} else {
		fmt.Fprintln(out, "附件仍在数据目录的 files/ 下：新服务端用同一个 -data 目录（或把它复制过去），加上 -database 启动即可。")
	}
	return nil
}
