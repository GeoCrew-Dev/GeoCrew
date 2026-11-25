package main

import (
	"encoding/xml"
	"fmt"
	"net/http"
	"strings"
	"time"
)

// exportChecks cover the downloads whose content the protocol fixes, as
// far as a client relies on it.
func (s *suite) exportChecks() []check {
	return []check{
		{section: "§6.13", name: "GPX 导出：轨迹按 times_s 给每个点时间，点成为航点", needs: "gpx", workspace: true, run: func() error {
			start := nowish().Add(-time.Hour)
			track := feature(randomID(), 1, nowish(), map[string]any{
				"kind": "track", "title": "一致性轨迹", "started_at": start.Format(time.RFC3339), "times_s": []int{0, 15, 40},
			})
			track["geometry"] = map[string]any{"type": "LineString", "coordinates": [][]float64{{116.39, 39.91, 50}, {116.391, 39.91, 52}, {116.392, 39.911, 55}}}
			point := feature(randomID(), 1, nowish(), map[string]any{"kind": "point", "title": "一致性航点"})
			if _, _, err := s.push(s.leader, track, point); err != nil {
				return err
			}
			r, err := s.c.do(http.MethodGet, wsPath(s.ws, "/export.gpx"), s.member, nil, nil)
			if err != nil {
				return err
			}
			if err := expect(r, http.StatusOK, ""); err != nil {
				return err
			}
			var doc struct {
				Wpts []struct {
					Name string `xml:"name"`
				} `xml:"wpt"`
				Trks []struct {
					Name string `xml:"name"`
					Segs []struct {
						Pts []struct {
							Time string `xml:"time"`
						} `xml:"trkpt"`
					} `xml:"trkseg"`
				} `xml:"trk"`
			}
			if err := xml.Unmarshal(r.body, &doc); err != nil {
				return fmt.Errorf("不是有效的 GPX：%v", err)
			}
			wpt := false
			for _, w := range doc.Wpts {
				wpt = wpt || strings.TrimSpace(w.Name) == "一致性航点"
			}
			if !wpt {
				return fmt.Errorf("点没有写成名为「一致性航点」的 wpt")
			}
			for _, t := range doc.Trks {
				if strings.TrimSpace(t.Name) != "一致性轨迹" {
					continue
				}
				if len(t.Segs) != 1 || len(t.Segs[0].Pts) != 3 {
					return fmt.Errorf("轨迹应为 1 段 3 个点")
				}
				for i, want := range []int{0, 15, 40} {
					got, err := time.Parse(time.RFC3339, strings.TrimSpace(t.Segs[0].Pts[i].Time))
					if err != nil || !got.Equal(start.Add(time.Duration(want)*time.Second)) {
						return fmt.Errorf("第 %d 个点的时间是 %q，应为 started_at 加 %d 秒", i+1, t.Segs[0].Pts[i].Time, want)
					}
				}
				return nil
			}
			return fmt.Errorf("没有找到名为「一致性轨迹」的 trk")
		}},
	}
}
