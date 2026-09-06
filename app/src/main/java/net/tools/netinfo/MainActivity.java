package net.tools.netinfo;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.RouteInfo;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileReader;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class MainActivity extends Activity {

    private static final String[] URLS_V4 = {
            "https://api4.ipify.org", "https://ipv4.icanhazip.com", "https://4.ipw.cn"};
    private static final String[] URLS_V6 = {
            "https://api6.ipify.org", "https://6.ipw.cn"};

    /** /proc/net/if_inet6 内核地址标志位 */
    private static final long IFA_F_DEPRECATED = 0x20L;
    private static final long IFA_F_PERMANENT = 0x80L;
    private static final long IFA_F_STABLE_PRIVACY = 0x800L;

    private static class Entry {
        final String label;
        String value;
        Entry(String label, String value) { this.label = label; this.value = value; }
    }

    private LinearLayout list;
    private final List<Entry> entries = new ArrayList<>();

    private TextView v4Text, v6Text;
    private Entry v4Entry, v6Entry;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        // 浅色状态栏/导航栏图标（MIUI 浅色风格）
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);

        list = findViewById(R.id.list);
        findViewById(R.id.btnRefresh).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { refresh(); }
        });
        findViewById(R.id.btnCopyAll).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { copyAll(); }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        list.removeAllViews();
        entries.clear();
        v4Text = null; v6Text = null;
        v4Entry = null; v6Entry = null;

        Map<String, Long> v6Flags = parseIfInet6Flags();
        String activeIf = getActiveInterfaceName();

        // —— 本机地址（置顶：IPv4/IPv6 内网、公网分组） ——
        addMyAddressesCard(activeIf, v6Flags);

        // —— 外网地址 ——
        LinearLayout publicBody = addCard("外网地址（公网出口）");
        View r4 = addRow(publicBody, "公网 IPv4（外网）", "查询中…");
        v4Text = r4.findViewById(R.id.value);
        v4Entry = (Entry) r4.getTag();
        View r6 = addRow(publicBody, "公网 IPv6（外网）", "查询中…");
        v6Text = r6.findViewById(R.id.value);
        v6Entry = (Entry) r6.getTag();

        // —— 当前网络 ——
        addActiveNetworkCard(activeIf);

        // —— 所有网卡 ——
        addInterfaceCards(v6Flags, activeIf);

        fetchAsync(URLS_V4, true);
        fetchAsync(URLS_V6, false);
    }

    // ============================ 卡片构建 ============================

    /** 置顶卡片：本机地址，按 IPv4 内网/公网、IPv6 内网/公网分组 */
    private void addMyAddressesCard(String activeIf, Map<String, Long> v6Flags) {
        LinearLayout body = addCard("本机地址");

        // 收集当前活动网络（或回退：所有启用接口）的地址
        List<InetAddress> addrs = new ArrayList<>();
        String ifname = activeIf;
        try {
            ConnectivityManager cm =
                    (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            Network net = cm == null ? null : cm.getActiveNetwork();
            LinkProperties lp = net == null ? null : cm.getLinkProperties(net);
            if (lp != null) {
                ifname = lp.getInterfaceName();
                for (android.net.LinkAddress la : lp.getLinkAddresses()) {
                    if (la != null && la.getAddress() != null) addrs.add(la.getAddress());
                }
            }
        } catch (Exception ignored) { }

        if (addrs.isEmpty()) {
            // 回退：扫描所有启用接口（排除回环）
            try {
                Enumeration<NetworkInterface> e = NetworkInterface.getNetworkInterfaces();
                while (e != null && e.hasMoreElements()) {
                    NetworkInterface ni = e.nextElement();
                    try {
                        if (!ni.isUp() || ni.isLoopback()) continue;
                        for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                            if (ia.getAddress() != null) addrs.add(ia.getAddress());
                        }
                    } catch (Exception ignored) { }
                }
            } catch (Exception ignored) { }
        }

        // 分组
        List<InetAddress> v4Private = new ArrayList<>();
        List<InetAddress> v4Public = new ArrayList<>();
        List<InetAddress> v6Private = new ArrayList<>();
        List<InetAddress> v6Public = new ArrayList<>();
        for (InetAddress a : addrs) {
            if (a instanceof Inet4Address) {
                if (isPrivateV4(a)) v4Private.add(a); else if (!a.isLoopbackAddress()
                        && !a.isLinkLocalAddress() && !a.isMulticastAddress()) v4Public.add(a);
            } else if (a instanceof Inet6Address) {
                if (isPrivateV6(a)) v6Private.add(a); else if (!a.isLoopbackAddress()
                        && !a.isLinkLocalAddress() && !a.isMulticastAddress()) v6Public.add(a);
            }
        }

        boolean any = false;
        any |= addGroupRows(body, "IPv4 内网地址", v4Private, null);
        any |= addGroupRows(body, "IPv4 公网地址", v4Public, null);
        any |= addGroupRows(body, "IPv6 内网地址", v6Private, v6Flags);
        any |= addGroupRows(body, "IPv6 公网地址", v6Public, v6Flags);
        if (!any) addRow(body, "状态", "未获取到地址");
    }

    /** 添加一组地址行，返回是否有内容 */
    private boolean addGroupRows(ViewGroup parent, String label, List<InetAddress> addrs,
                                 Map<String, Long> v6Flags) {
        if (addrs.isEmpty()) return false;
        int i = 1;
        for (InetAddress a : addrs) {
            String rowLabel = addrs.size() > 1
                    ? label + " " + (i++) : label;
            String extra = "";
            if (v6Flags != null && a instanceof Inet6Address) {
                String kind = v6KindText((Inet6Address) a, v6Flags);
                if (kind != null) extra = "（" + kind + "）";
            }
            addRow(parent, rowLabel + extra, stripZone(a.getHostAddress()), true);
        }
        return true;
    }

    /** IPv6 临时/非临时描述（供置顶卡片使用） */
    private String v6KindText(Inet6Address a, Map<String, Long> v6Flags) {
        String ifname = getActiveInterfaceName();
        if (ifname == null) return null;
        Long f = v6Flags.get(hexKey(a, ifname));
        if (f == null) return null;
        boolean permanent = (f & IFA_F_PERMANENT) != 0;
        boolean deprecated = (f & IFA_F_DEPRECATED) != 0;
        String kind = permanent ? "非临时" : "临时";
        if (deprecated) kind += "，已弃用";
        return kind;
    }

    private static boolean isPrivateV4(InetAddress a) {
        return a.isSiteLocalAddress() || a.isLinkLocalAddress() || a.isLoopbackAddress();
    }

    private static boolean isPrivateV6(InetAddress a) {
        byte[] b = a.getAddress();
        if (b != null && b.length == 16 && (b[0] & 0xFE) == 0xFC) return true; // ULA fc00::/7
        return a.isLinkLocalAddress() || a.isLoopbackAddress();
    }

    private LinearLayout addCard(String title) {
        View card = LayoutInflater.from(this).inflate(R.layout.item_card, list, false);
        ((TextView) card.findViewById(R.id.cardTitle)).setText(title);
        list.addView(card);
        return (LinearLayout) card.findViewById(R.id.cardBody);
    }

    private View addRow(ViewGroup parent, String label, String value) {
        return addRow(parent, label, value, false);
    }

    /** box=true：值显示为不可编辑的文本框，可长按调出选择工具复制 */
    private View addRow(ViewGroup parent, String label, String value, boolean box) {
        final Entry e = new Entry(label, value);
        entries.add(e);
        View row = LayoutInflater.from(this)
                .inflate(box ? R.layout.item_row_box : R.layout.item_row, parent, false);
        ((TextView) row.findViewById(R.id.label)).setText(label);
        if (box) {
            EditText et = (EditText) row.findViewById(R.id.value);
            et.setText(value);
            et.setKeyListener(null);          // 不可编辑、不弹输入法
            et.setTextIsSelectable(true);     // 长按可调出选择/复制工具
            et.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14);
        } else {
            ((TextView) row.findViewById(R.id.value)).setText(value);
        }
        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { copy(e.label, e.value); }
        });
        row.setTag(e);
        parent.addView(row);
        return row;
    }

    private void addActiveNetworkCard(String activeIf) {
        LinearLayout body = addCard("当前网络");
        ConnectivityManager cm =
                (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        Network net = cm == null ? null : cm.getActiveNetwork();
        if (net == null) {
            addRow(body, "状态", "未联网");
            return;
        }
        addRow(body, "状态", "已联网");

        NetworkCapabilities caps = cm.getNetworkCapabilities(net);
        if (caps != null) addRow(body, "网络类型", describeTransports(caps));

        LinkProperties lp = cm.getLinkProperties(net);
        if (lp == null) return;

        if (lp.getInterfaceName() != null) addRow(body, "网络接口", lp.getInterfaceName());

        String gw4 = null, gw6 = null;
        for (RouteInfo route : lp.getRoutes()) {
            try {
                if (route == null || route.getDestination() == null) continue;
                if (route.getDestination().getPrefixLength() != 0) continue; // 只看默认路由
                InetAddress gw = route.getGateway();
                if (gw == null) continue;
                String g = stripZone(gw.getHostAddress());
                if (gw instanceof Inet6Address) {
                    if (gw6 == null) gw6 = g;
                } else if (gw4 == null) {
                    gw4 = g;
                }
            } catch (Exception ignored) { }
        }
        if (gw4 != null) addRow(body, "默认网关（IPv4）", gw4);
        if (gw6 != null) addRow(body, "默认网关（IPv6）", gw6);

        List<InetAddress> dns = lp.getDnsServers();
        if (dns != null && !dns.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (InetAddress d : dns) {
                if (sb.length() > 0) sb.append('\n');
                sb.append(stripZone(d.getHostAddress()));
            }
            addRow(body, "DNS 服务器", sb.toString());
        }

        String domains = lp.getDomains();
        if (domains != null && !domains.isEmpty()) addRow(body, "搜索域名", domains);

        boolean unmetered = caps != null
                && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED);
        addRow(body, "计费网络", unmetered ? "否" : "是");
    }

    private void addInterfaceCards(Map<String, Long> v6Flags, String activeIf) {
        List<NetworkInterface> up = new ArrayList<>();
        List<NetworkInterface> down = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> e = NetworkInterface.getNetworkInterfaces();
            while (e != null && e.hasMoreElements()) {
                NetworkInterface ni = e.nextElement();
                try {
                    if (ni.isUp()) up.add(ni); else down.add(ni);
                } catch (Exception ignored) { }
            }
        } catch (Exception ignored) { }

        final String act = activeIf == null ? "" : activeIf;
        Comparator<NetworkInterface> order = new Comparator<NetworkInterface>() {
            @Override public int compare(NetworkInterface a, NetworkInterface b) {
                return score(a, act) - score(b, act);
            }
        };
        Collections.sort(up, order);

        for (NetworkInterface ni : up) addInterfaceCard(ni, v6Flags, true);
        for (NetworkInterface ni : down) {
            try {
                // 停用且无地址的虚拟接口直接跳过，减少干扰
                if (ni.getInterfaceAddresses().isEmpty() && !isInterestingName(ni.getName())) continue;
            } catch (Exception ignored) { }
            addInterfaceCard(ni, v6Flags, false);
        }
    }

    private static int score(NetworkInterface ni, String activeIf) {
        String n = safeName(ni);
        if (n.equals(activeIf)) return 0;
        try { if (ni.isLoopback()) return 2; } catch (Exception ignored) { }
        return 1;
    }

    private static String safeName(NetworkInterface ni) {
        try { String n = ni.getName(); return n == null ? "" : n; } catch (Exception e) { return ""; }
    }

    private static boolean isInterestingName(String name) {
        if (name == null) return false;
        String[] prefixes = {"wlan", "eth", "rmnet", "ccmni", "tun", "tap", "usb", "wan"};
        for (String p : prefixes) if (name.startsWith(p)) return true;
        return false;
    }

    private void addInterfaceCard(NetworkInterface ni, Map<String, Long> v6Flags, boolean isUp) {
        try {
            String name = ni.getName();
            String display = ni.getDisplayName();
            String title = (display != null && !display.isEmpty() && !display.equals(name))
                    ? name + " · " + display : name;
            if (!isUp) title += " · 已停用";
            LinearLayout body = addCard(title);

            addRow(body, "状态", isUp ? "启用" : "停用");

            String type = null;
            try {
                if (ni.isLoopback()) type = "回环";
                else if (ni.isPointToPoint()) type = "点对点";
                else if (ni.isVirtual()) type = "虚拟";
            } catch (Exception ignored) { }
            if (type != null) addRow(body, "类型", type);

            try {
                byte[] mac = ni.getHardwareAddress();
                if (mac != null && mac.length >= 6 && !ni.isLoopback())
                    addRow(body, "MAC 地址", formatMac(mac));
            } catch (Exception ignored) { }

            try {
                int mtu = ni.getMTU();
                if (mtu > 0) addRow(body, "MTU", String.valueOf(mtu));
            } catch (Exception ignored) { }

            for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                InetAddress a = ia.getAddress();
                if (a == null) continue;
                String value = stripZone(a.getHostAddress()) + "/" + ia.getNetworkPrefixLength();
                String label;
                if (a instanceof Inet4Address) {
                    label = "IPv4 · " + classifyV4(a);
                } else {
                    label = "IPv6 · " + classifyV6(a) + v6Extra(a, name, v6Flags);
                }
                addRow(body, label, value);
            }
        } catch (Exception ignored) { }
    }

    // ============================ 地址分类 ============================

    private static String classifyV4(InetAddress a) {
        if (a.isAnyLocalAddress()) return "通配地址";
        if (a.isLoopbackAddress()) return "回环";
        if (a.isLinkLocalAddress()) return "链路本地（169.254）";
        if (a.isSiteLocalAddress()) return "内网（私有地址）";
        if (a.isMulticastAddress()) return "组播";
        return "公网";
    }

    private static String classifyV6(InetAddress a) {
        if (a.isAnyLocalAddress()) return "通配地址";
        if (a.isLoopbackAddress()) return "回环";
        if (a.isLinkLocalAddress()) return "链路本地（fe80）";
        if (a.isMulticastAddress()) return "组播";
        byte[] b = a.getAddress();
        if (b != null && b.length == 16) {
            if ((b[0] & 0xFE) == 0xFC) return "内网（ULA 唯一本地）";
            if ((b[0] & 0xFF) == 0xFE && (b[1] & 0xC0) == 0xC0) return "站点本地（已废弃）";
        }
        if (a.isSiteLocalAddress()) return "站点本地（已废弃）";
        return "全球公网";
    }

    /** 依据 /proc/net/if_inet6 的标志位判断临时（隐私）/非临时地址，仅对全球公网地址有意义 */
    private String v6Extra(InetAddress a, String ifname, Map<String, Long> v6Flags) {
        if (!(a instanceof Inet6Address)) return "";
        if (a.isLoopbackAddress() || a.isLinkLocalAddress() || a.isMulticastAddress()) return "";
        Long f = v6Flags.get(hexKey(a, ifname));
        if (f == null) return "";
        boolean permanent = (f & IFA_F_PERMANENT) != 0;
        boolean stable = (f & IFA_F_STABLE_PRIVACY) != 0;
        boolean deprecated = (f & IFA_F_DEPRECATED) != 0;
        String kind = permanent
                ? (stable ? "非临时（稳定隐私标识）" : "非临时（EUI-64 / 静态）")
                : "临时（隐私扩展）";
        if (deprecated) kind += "，已弃用";
        return " · " + kind;
    }

    // ============================ 数据获取 ============================

    private String getActiveInterfaceName() {
        try {
            ConnectivityManager cm =
                    (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            Network net = cm == null ? null : cm.getActiveNetwork();
            if (net == null) return null;
            LinkProperties lp = cm.getLinkProperties(net);
            return lp == null ? null : lp.getInterfaceName();
        } catch (Exception e) {
            return null;
        }
    }

    /** 解析 /proc/net/if_inet6，返回 key=32位hex地址%接口名 -> flags */
    private Map<String, Long> parseIfInet6Flags() {
        Map<String, Long> map = new HashMap<>();
        File f = new File("/proc/net/if_inet6");
        if (!f.canRead()) return map;
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new FileReader(f));
            String line;
            while ((line = reader.readLine()) != null) {
                String[] p = line.trim().split("\\s+");
                if (p.length >= 6) {
                    try {
                        map.put(p[0].toLowerCase(Locale.US) + "%" + p[5],
                                Long.parseLong(p[4], 16));
                    } catch (NumberFormatException ignored) { }
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (reader != null) try { reader.close(); } catch (Exception ignored) { }
        }
        return map;
    }

    private String hexKey(InetAddress a, String ifname) {
        byte[] b = a.getAddress();
        StringBuilder sb = new StringBuilder(b.length * 2 + ifname.length() + 1);
        for (byte x : b) sb.append(String.format(Locale.US, "%02x", x));
        return sb.append('%').append(ifname).toString();
    }

    private void fetchAsync(final String[] specs, final boolean v4) {
        new Thread(new Runnable() {
            @Override public void run() {
                String result = null;
                for (String spec : specs) {
                    result = httpGet(spec);
                    if (result != null) break;
                }
                final String ip = result;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        TextView tv = v4 ? v4Text : v6Text;
                        Entry e = v4 ? v4Entry : v6Entry;
                        String shown = ip != null ? ip
                                : "获取失败（无 IPv" + (v4 ? "4" : "6") + " 出口或网络不可用）";
                        if (e != null) e.value = ip != null ? ip : "获取失败";
                        if (tv != null) tv.setText(shown);
                    }
                });
            }
        }).start();
    }

    private String httpGet(String spec) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(spec).openConnection();
            conn.setConnectTimeout(6000);
            conn.setReadTimeout(6000);
            conn.setRequestProperty("User-Agent", "NetInfo/1.0");
            if (conn.getResponseCode() != 200) return null;
            InputStream in = conn.getInputStream();
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[256];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            in.close();
            String s = bo.toString("UTF-8").trim();
            return s.isEmpty() ? null : s;
        } catch (Exception e) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    // ============================ 复制 ============================

    private void copy(String label, String value) {
        copyToClipboard(label, value);
        String shown = value.length() > 40 ? "已复制（内容较长）" : "已复制 " + value;
        Toast.makeText(this, shown, Toast.LENGTH_SHORT).show();
    }

    private void copyAll() {
        if (entries.isEmpty()) {
            Toast.makeText(this, "没有可复制的信息", Toast.LENGTH_SHORT).show();
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (Entry e : entries) sb.append(e.label).append("：").append(e.value).append('\n');
        copyToClipboard("全部网络信息", sb.toString().trim());
        Toast.makeText(this, "已复制全部网络信息", Toast.LENGTH_SHORT).show();
    }

    private void copyToClipboard(String label, String value) {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText(label, value));
    }

    // ============================ 工具 ============================

    private String describeTransports(NetworkCapabilities caps) {
        List<String> t = new ArrayList<>();
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) t.add("Wi-Fi");
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) t.add("移动数据");
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) t.add("以太网");
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)) t.add("蓝牙");
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) t.add("VPN");
        if (t.isEmpty()) return "其他";
        StringBuilder sb = new StringBuilder();
        for (String s : t) {
            if (sb.length() > 0) sb.append(" + ");
            sb.append(s);
        }
        return sb.toString();
    }

    private static String stripZone(String s) {
        if (s == null) return "";
        int i = s.indexOf('%');
        return i >= 0 ? s.substring(0, i) : s;
    }

    private static String formatMac(byte[] mac) {
        StringBuilder sb = new StringBuilder(mac.length * 3 - 1);
        for (int i = 0; i < mac.length; i++) {
            if (i > 0) sb.append(':');
            sb.append(String.format(Locale.US, "%02X", mac[i]));
        }
        return sb.toString();
    }
}
