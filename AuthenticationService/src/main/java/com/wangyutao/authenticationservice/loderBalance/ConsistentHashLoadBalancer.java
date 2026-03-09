package com.wangyutao.authenticationservice.loderBalance;

import org.springframework.cloud.client.ServiceInstance;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.stream.Collectors;

@Component
@Slf4j
public class ConsistentHashLoadBalancer implements LoadBalancer {
    private static final int VIRTUAL_NODES = 160;

    private volatile TreeMap<Integer, ServiceInstance> hashRing = new TreeMap<>();

    private volatile String lastInstanceSignature = "";


    @Override
    public ServiceInstance select(List<ServiceInstance> instances, String userId) {
        if (instances == null || instances.isEmpty()) {
            log.warn("❌ 当前没有可用的 Netty 实例！");
            return null;
        }

        // 生成当前实例列表的签名 (例如: "192.168.1.1:9100,192.168.1.2:9100")
        String currentSignature = instances.stream()
                .map(instance -> instance.getHost() + ":" + instance.getPort())
                .sorted()
                .collect(Collectors.joining(","));

        // 🌟 3. 如果服务器列表发生变化（扩容或宕机），才重新构建哈希环
        if (!currentSignature.equals(lastInstanceSignature)) {
            synchronized (this) {
                // 双重检查锁，防止高并发下重复构建
                if (!currentSignature.equals(lastInstanceSignature)) {
                    buildHashRing(instances);
                    lastInstanceSignature = currentSignature;
                }
            }
        }

        // 🌟 4. 路由逻辑：寻找哈希环上的对应节点
        int hash = getHash(userId);
        // 得到大于等于该 Hash 值的右侧红黑树
        SortedMap<Integer, ServiceInstance> tailMap = hashRing.tailMap(hash);

        // 如果右侧没有节点了，说明越界了，折返到环的第一个节点
        Integer targetKey = tailMap.isEmpty() ? hashRing.firstKey() : tailMap.firstKey();

        return hashRing.get(targetKey);
    }

    /**
     * 构建哈希环 (只有实例列表变动时才会调用，性能极高)
     */
    private void buildHashRing(List<ServiceInstance> instances) {
        TreeMap<Integer, ServiceInstance> newHashRing = new TreeMap<>();
        for (ServiceInstance instance : instances) {
            String url = instance.getHost() + ":" + instance.getPort();
            // 添加真实节点
            newHashRing.put(getHash(url), instance);
            // 添加虚拟节点，使数据分布更均匀
            for (int i = 0; i < VIRTUAL_NODES; i++) {
                int virtualHash = getHash(url + "#" + i);
                newHashRing.put(virtualHash, instance);
            }
        }
        this.hashRing = newHashRing; // 原子性替换旧环
        log.info("🔄 Netty 节点发生变化，一致性哈希环重构完成！当前节点数: {}, 虚拟节点总数: {}",
                instances.size(), newHashRing.size());
    }

    /**
     * 补习班原汁原味的 FNV1_32_HASH 算法 (极其适合做散列)
     */
    private int getHash(String str) {
        final int p = 16777619;
        int hash = (int) 2166136261L;
        for (int i = 0; i < str.length(); i++) {
            hash = (hash ^ str.charAt(i)) * p;
            hash += hash << 13;
            hash ^= hash >> 7;
            hash += hash << 3;
            hash ^= hash >> 17;
            hash += hash << 5;
            if (hash < 0) {
                hash = Math.abs(hash); // 简化了位运算取绝对值，更加直观
            }
        }
        return hash;
    }
}
