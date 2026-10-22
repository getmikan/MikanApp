package snapshot

import (
	"bytes"
	"crypto/sha256"
	"fmt"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"sync/atomic"
	"testing"

	"github.com/metacubex/mihomo/component/geodata"
	"github.com/metacubex/mihomo/component/geodata/router"
	"github.com/metacubex/mihomo/component/updater"
	C "github.com/metacubex/mihomo/constant"
	"google.golang.org/protobuf/proto"
)

// Use the Android asset names and engine entry points, with no public network.
func TestAndroidGeoAssetsAndUpdates(t *testing.T) {
	home, policy := C.Path.HomeDir(), geodata.CurrentVerificationPolicy()
	ipName, siteName := C.GeoipName, C.GeositeName
	ipURL, siteURL := geodata.GeoIpUrl(), geodata.GeoSiteUrl()
	clearCaches := func() {
		geodata.ClearGeoIPCache()
		geodata.ClearGeoSiteCache()
	}
	t.Cleanup(func() {
		C.SetHomeDir(home)
		C.GeoipName, C.GeositeName = ipName, siteName
		geodata.SetVerificationPolicy(policy)
		geodata.SetGeoIpUrl(ipURL)
		geodata.SetGeoSiteUrl(siteURL)
		clearCaches()
	})
	C.SetHomeDir(t.TempDir())
	clearCaches()

	ip, err := proto.Marshal(&router.GeoIPList{Entry: []*router.GeoIP{{
		CountryCode: "CN", Cidr: []*router.CIDR{{Ip: []byte{203, 0, 113, 0}, Prefix: 24}},
	}}})
	if err != nil {
		t.Fatal(err)
	}
	site, err := proto.Marshal(&router.GeoSiteList{Entry: []*router.GeoSite{{
		CountryCode: "CN", Domain: []*router.Domain{{Type: router.Domain_Domain, Value: "example.cn"}},
	}}})
	if err != nil {
		t.Fatal(err)
	}
	var requests atomic.Int32
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		requests.Add(1)
		switch r.URL.Path {
		case "/geoip.dat":
			_, _ = w.Write(ip)
		case "/geosite.dat":
			_, _ = w.Write(site)
		default:
			http.NotFound(w, r)
		}
	}))
	defer server.Close()
	yaml := fmt.Sprintf(`geodata-mode: true
geo-auto-update: false
geox-url:
  geoip: %s/geoip.dat
  geosite: %s/geosite.dat
rules:
  - GEOSITE,cn,DIRECT
  - GEOIP,cn,DIRECT
  - MATCH,DIRECT
`, server.URL, server.URL)
	for name, data := range map[string][]byte{"geoip.dat": ip, "geosite.dat": site} {
		if err := os.WriteFile(filepath.Join(C.Path.HomeDir(), name), data, 0o600); err != nil {
			t.Fatal(err)
		}
	}
	if message := ValidateBytes([]byte(yaml)); message != "" {
		t.Fatalf("bundled databases without pins rejected: %s", message)
	}
	if requests.Load() != 0 {
		t.Fatal("valid bundled databases triggered a download")
	}
	for _, path := range []string{C.Path.GeoIP(), C.Path.GeoSite()} {
		if err := os.Remove(path); err != nil {
			t.Fatal(err)
		}
	}
	clearCaches()
	if message := ValidateBytes([]byte(yaml)); message != "" {
		t.Fatalf("missing databases without pins could not download: %s", message)
	}
	if requests.Load() != 2 {
		t.Fatalf("expected two missing-file downloads, got %d", requests.Load())
	}
	geodata.SetGeoIpUrl(server.URL + "/geoip.dat")
	geodata.SetGeoSiteUrl(server.URL + "/geosite.dat")
	for _, asset := range []struct {
		name   string
		path   string
		data   []byte
		update func() error
		policy func(string) geodata.VerificationPolicy
	}{
		{"geoip", C.Path.GeoIP(), ip, updater.UpdateGeoIp, func(pin string) geodata.VerificationPolicy { return geodata.VerificationPolicy{GeoIP: pin} }},
		{"geosite", C.Path.GeoSite(), site, updater.UpdateGeoSite, func(pin string) geodata.VerificationPolicy { return geodata.VerificationPolicy{GeoSite: pin} }},
	} {
		t.Run(asset.name, func(t *testing.T) {
			if err := os.WriteFile(asset.path, []byte("stale database"), 0o600); err != nil {
				t.Fatal(err)
			}
			for _, pin := range []string{"", fmt.Sprintf("%x", sha256.Sum256(asset.data))} {
				geodata.SetVerificationPolicy(asset.policy(pin))
				if err := asset.update(); err != nil {
					t.Fatalf("manual update rejected (pin=%q): %v", pin, err)
				}
			}
			geodata.SetVerificationPolicy(asset.policy(strings.Repeat("0", 64)))
			if err := asset.update(); err == nil {
				t.Fatal("manual update ignored an explicit wrong pin")
			}
			got, err := os.ReadFile(asset.path)
			if err != nil || !bytes.Equal(got, asset.data) {
				t.Fatalf("failed update damaged installed database: %v", err)
			}
		})
	}
}
