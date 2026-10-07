package io.usblink.mobile;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Exact tagged rules only: never flush tables or weaken authentication. */
final class TcpFirewall {
    interface Runner {String run(String... command) throws IOException;}
    private final Runner runner;
    private int orderRepairs;
    TcpFirewall(Runner runner){this.runner=runner;}
    private static final String[][] RULES={
        {"INPUT","!","-i","lo","-p","tcp","--dport","15558","-m","comment","--comment","usblink-adbd-private","-j","DROP"},
        {"OUTPUT","-o","lo","-p","tcp","--dport","15558","-m","owner","!","--uid-owner","0","-m","comment","--comment","usblink-adbd-local","-j","REJECT"},
        {"INPUT","!","-i","usblink0","-p","tcp","--dport","3242","-m","comment","--comment","usblink-adbd-mesh","-j","DROP"},
        {"INPUT","!","-i","usblink0","-p","tcp","--dport","3240","-m","comment","--comment","usblink-usb-mesh","-j","DROP"}
    };
    private String execute(String binary,String op,String[] rule)throws IOException {
        List<String> args=new ArrayList<>(Arrays.asList(binary,"-w","2",op,rule[0]));
        if(op.equals("-I"))args.add("1");
        args.addAll(Arrays.asList(rule).subList(1,rule.length));
        return runner.run(args.toArray(new String[0]));
    }
    void install()throws IOException {
        try{verify();return;}catch(IOException absentOrMoved){ /* Add restrictive rules first, without opening an existing port. */ }
        prepend();
    }
    private void prepend()throws IOException {
        for(String binary:new String[]{"/system/bin/iptables","/system/bin/ip6tables"})for(String[] rule:RULES)execute(binary,"-I",rule);
        verify();
    }
    private static final class OrderChanged extends IOException {
        OrderChanged(){super("调试防护规则优先级发生变化");}
    }
    // Called by the serialized service lifecycle, never by a WebUI status read.
    // All exact rules in BOTH families must still exist before order repair.
    void maintain()throws IOException {
        try{verify();}
        catch(OrderChanged moved) {
            // Recheck immediately before mutation. Unlike initial install/recovery,
            // maintenance must not catch missing-rule errors and recreate protection.
            try{verify();return;}catch(OrderChanged stillMoved){ /* Still complete, but displaced. */ }
            // Bound duplicates if another root component keeps competing for chain head.
            if(orderRepairs>=3)throw new IOException("其他网络组件持续改写调试防护顺序，共享已暂停，请检查防火墙模块");
            orderRepairs++;
            prepend();
        }
    }
    String snapshot()throws IOException {
        StringBuilder state=new StringBuilder();
        for(String binary:new String[]{"/system/bin/iptables","/system/bin/ip6tables"})
            for(String chain:new String[]{"INPUT","OUTPUT"})state.append(binary).append('/').append(chain).append('\n').append(runner.run(binary,"-w","2","-S",chain)).append('\n');
        return state.toString();
    }
    void verify()throws IOException {
        for(String binary:new String[]{"/system/bin/iptables","/system/bin/ip6tables"})
            for(String[] rule:RULES)execute(binary,"-C",rule);
        for(String binary:new String[]{"/system/bin/iptables","/system/bin/ip6tables"}) {
            for(String chain:new String[]{"INPUT","OUTPUT"}) {
                List<String> entries=new ArrayList<>();
                for(String line:runner.run(binary,"-w","2","-S",chain).split("\\r?\\n"))if(line.startsWith("-A "+chain+" "))entries.add(line);
                if(chain.equals("INPUT")) {
                    if(entries.size()<3 || !sameRule(entries.get(0),RULES[3]) || !sameRule(entries.get(1),RULES[2]) || !sameRule(entries.get(2),RULES[0]))throw new OrderChanged();
                } else if(entries.isEmpty() || !sameRule(entries.get(0),RULES[1]))throw new OrderChanged();
            }
        }
    }
    private static boolean sameRule(String line,String[] rule) {
        // iptables -S adds the protocol module, quotes comments, and prints REJECT's default.
        String canonical=line.replace(" -m tcp","")
            .replaceAll("--comment [\"'](usblink-[a-z-]+)[\"']","--comment $1")
            .replaceAll(" --reject-with (icmp-port-unreachable|icmp6-port-unreachable)$","");
        return canonical.equals("-A "+String.join(" ",rule));
    }
    void remove()throws IOException {
        for(String binary:new String[]{"/system/bin/iptables","/system/bin/ip6tables"})for(String[] rule:RULES) {
            // Inspect exact tagged ownership on every pass; stop on failures rather than flush.
            String tag=rule[Arrays.asList(rule).indexOf("--comment")+1];
            int count=0;
            while(runner.run(binary,"-w","2","-S",rule[0]).contains(tag) && count++<12) {
                execute(binary,"-C",rule);execute(binary,"-D",rule);
            }
            if(runner.run(binary,"-w","2","-S",rule[0]).contains(tag))throw new IOException("模块防护规则尚未清理，请重试关闭");
        }
        orderRepairs=0;
    }
}
