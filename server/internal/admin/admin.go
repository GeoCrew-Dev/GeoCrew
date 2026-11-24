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

	"github.com/GeoCrew-Dev/GeoCrew-Server/internal/export"
	"github.com/GeoCrew-Dev/GeoCrew-Server/internal/store"
)

// Usage is printed for "admin help" and for unknown commands.
const Usage = `用法：geocrew-server [参数] admin <命令>

  workspaces                       列出所有工作组
  members <工作组>                  列出成员（含已移出的）
  invite <工作组>                   更换邀请码并显示新码
  role <工作组> <成员> <角色>        修改角色：leader / member / readonly
  revoke <工作组> <成员>            移出成员，令牌立即失效
  backup <目标文件>                 在线备份数据库（附件目录另行复制）
  export <工作组> <目标文件>         导出当前全部记录为 GeoJSON（可直接用 QGIS 打开）

<工作组> 和 <成员> 可以写 ID 或其开头几位，只要能唯一确定。
`

// Run executes one admin command against st.
func Run(ctx context.Context, st store.Store, args []string, out io.Writer) error {
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
		n, err := export.GeoJSON(ctx, st, ws.ID, f)
		if cerr := f.Close(); err == nil {
			err = cerr
		}
		if err != nil {
			os.Remove(rest[1])
			return err
		}
		fmt.Fprintf(out, "已把「%s」的 %d 条记录导出到 %s\n", ws.Name, n, rest[1])
		return nil
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
	fmt.Fprintln(tw, "ID\t昵称\t角色\t加入时间\t状态")
	for _, m := range ms {
		state := ""
		if m.Revoked {
			state = "已移出"
		}
		fmt.Fprintf(tw, "%s\t%s\t%s\t%s\t%s\n", m.ID, m.Nickname, m.Role, m.JoinedAt.Local().Format(time.DateTime), state)
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
