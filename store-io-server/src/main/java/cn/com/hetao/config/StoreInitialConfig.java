package cn.com.hetao.config;

import cn.com.common.hetao.entity.fileio.FileConfig;
import cn.com.hetao.io.FileIoFactoryAdaptor;
import cn.com.hetao.io.FileIoFactoryConfig;
import cn.com.hetao.io.config.KeyObjectDefination;
import cn.com.hetao.property.StoreProperty;
import cn.com.hetao.server.event.NoticeEventResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/*
 *@username LUOYUSHUN
 *@datetime 2020/2/29 15:11
 *@desc 这个是对数据进行初始化
 **/
@Configuration
@Slf4j
public class StoreInitialConfig implements InitializingBean {

    @Autowired
    private StoreProperty storeProperty;

    @Override
    public void afterPropertiesSet() throws Exception {
        initConfig();
        StoreBean.cacheSize = storeProperty.getCacheSize();
        initKey();
        StoreBean.reflushDisk = new ScheduledThreadPoolExecutor(20);
        initRefreshDisk();
        whileTrue();
        StoreBean.registerEvent.add(new NoticeEventResponse());
    }

    // 对配置文件进行配置
    private void initConfig() {
        log.info("开始初始化配置文件");
        FileConfig fileConfig = new FileConfig();
        fileConfig.setIndexPath(storeProperty.getIndexPath());
        fileConfig.setDataPath(storeProperty.getDataPath());
        fileConfig.setMaxSize(storeProperty.getMaxFileSize());
        fileConfig.setDateTempPath(storeProperty.getDataTempPath());
        FileIoFactoryConfig config = new FileIoFactoryAdaptor();
        config.setConfig(fileConfig);
        log.info("初始化配置文件完成");
    }

    /**
     * 这个是初始化数据
     */
    private void initKey() {
        log.info("开始初始化key");
        try {
            List<KeyObjectDefination> keys = StoreBean.keyFactory.getKeysInfo();
            if (keys == null) return;
            for (KeyObjectDefination defination: keys) {
                defination.setDisk(true);
                defination.setDelete(false);
                StoreBean.keyBeans.put(defination.getName(), defination);
            }
        }catch (Exception e) {
            e.printStackTrace();
        }
        log.info("初始化key结束");
    }

    private void whileTrue() {
        (new Thread(new Runnable() {
            @Override
            public void run() {
                while (true) {
                    synchronized (StoreInitialConfig.this) {
                        try {
                            StoreInitialConfig.this.wait();
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    }
                    initRefreshDisk();
                }
            }
        })).start();
    }

    /**
     * 这个是定时刷新磁盘
     */
    private void initRefreshDisk() {
        StoreBean.reflushDisk.schedule(new Runnable() {
            @Override
            public void run() {
                log.debug("开始刷盘");
                List<KeyObjectDefination> definations = new ArrayList<>();
                synchronized (StoreBean.keyBeans) {
                    for (ConcurrentMap.Entry<String, KeyObjectDefination> entry : StoreBean.keyBeans.entrySet()) {
                        definations.add(entry.getValue());
                    }
                }
                try {
                    StoreBean.kvRefreshDisk.refreshValue(definations);
                    definations = null;
                    StoreBean.keyBeans.clear();
                    StoreBean.valueBeans.clear();
                    StoreBean.valueBeanList.clear();
                    initKey();
                }catch (Exception e) {
                    e.printStackTrace();
                }
                log.debug("结束刷盘");
                synchronized (StoreInitialConfig.this) {
                    StoreInitialConfig.this.notify();
                }
            }
        }, storeProperty.getFlushDisk(), TimeUnit.MILLISECONDS);
    }

}
