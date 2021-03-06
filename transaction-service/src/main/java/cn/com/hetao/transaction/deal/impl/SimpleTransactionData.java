package cn.com.hetao.transaction.deal.impl;

import cn.com.common.hetao.entity.TransactionDefinationEntity;
import cn.com.common.hetao.utils.GainThisComputerInfo;
import cn.com.hetao.config.RedisOperation;
import cn.com.hetao.config.TransactionContainorBean;
import cn.com.common.hetao.enums.LockStatus;
import cn.com.common.hetao.enums.ReleaseStatus;
import cn.com.hetao.instep.SimpleInStepOtherServer;
import cn.com.hetao.netty.ContainorDataDeal;
import cn.com.hetao.transaction.deal.TransactionDataDealAbs;
import cn.com.hetao.transaction.compare.ContainorDataDealCompara;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;

/*
 *@username LUOYUSHUN
 *@datetime 2020/2/19 15:37
 *@desc
 **/
@Component
@Slf4j
public class SimpleTransactionData extends TransactionDataDealAbs {

    /**
     * 这个是绑定的端口
     */
    @Value("${connection.port}")
    private Integer port;

    @Autowired
    private RedisOperation redisOperation;

    @Autowired
    private SimpleInStepOtherServer otherServer;

    @Override
    public boolean requestResource(ContainorDataDeal dataDeal) {
        try {
            ContainorDataDeal containorDataDeal = TransactionContainorBean.dataGainDeals.get(dataDeal.getDefinationEntity().getResourcesId());
            if (containorDataDeal != null && containorDataDeal.getDefinationEntity().getId().longValue()
                    == dataDeal.getDefinationEntity().getId().longValue()) {
                dataDeal.getDefinationEntity().setGainLockTime(new Date());
                dataDeal.sendMessages(LockStatus.SUCCESS.value(), "成功上锁");
                return true;
            }
//            synchronized (TransactionContainorBean.dataWaitDeals) {
                List<ContainorDataDeal> dataDeals = TransactionContainorBean.dataWaitDeals.get(dataDeal.getDefinationEntity().getResourcesId());
                if (dataDeals == null) dataDeals = new ArrayList<>();
                synchronized (dataDeals) {
                    dataDeals.add(dataDeal);
                    TransactionContainorBean.dataWaitDeals.put(dataDeal.getDefinationEntity().getResourcesId(), dataDeals);
                }
                if (dataDeals.size() == 1 && containorDataDeal == null) {
                    dataDeal.getDefinationEntity().setGainLockTime(new Date());
                    findGainResource(dataDeal);
                } else {
                    dataDeal.getDefinationEntity().setLockStatus(LockStatus.LOCKING.value());
                    dataDeal.sendMessages(LockStatus.LOCKING.value(), "等待请求资源");
                    this.findGainResource(dataDeal);
                }
//            }
        }catch (Exception e){
            e.printStackTrace();
        }
        return true;
    }

    @Override
    public boolean findGainResource(ContainorDataDeal containorDataDeal) throws Exception {
        List<ContainorDataDeal> dataDeals = TransactionContainorBean.dataWaitDeals.get(containorDataDeal.getDefinationEntity().getResourcesId());
        if (dataDeals == null) return true;
        synchronized (dataDeals) {
            ContainorDataDeal con = TransactionContainorBean.dataGainDeals.get(containorDataDeal.getDefinationEntity().getResourcesId());
            if (dataDeals.isEmpty()) {
                if (con == null) {
                    otherServer.inStepOneToOtherServer(containorDataDeal.getDefinationEntity());
                }
                return true;
            }
            Collections.sort(dataDeals,new ContainorDataDealCompara());
            ContainorDataDeal dataDeal = dataDeals.get(0);
            if (con != null && dataDeal.getDefinationEntity().getId().longValue() == con.getDefinationEntity().getId().longValue()) {
                dataDeals.remove(0);
                return true;
            } else if (con != null) {
                return true;
            }
            if (con == null) {
                // 这里是检查数据是否进行了
                TransactionDefinationEntity definationEntity = dataDeal.getDefinationEntity();
                definationEntity.setIp(GainThisComputerInfo.getIp());
                definationEntity.setPort(port.intValue());
                boolean status = redisOperation.saveRedis(definationEntity);
                if (!status) {
                    return false;
                }
            }
            dataDeals.remove(0);
            dataDeal.getDefinationEntity().setGainLockTime(new Date());
            log.info("成功获取锁");
            dataDeal.sendMessages(LockStatus.SUCCESS.value().intValue(), "成功获取锁");
            TransactionContainorBean.dataGainDeals.put(dataDeal.getDefinationEntity().getResourcesId(), dataDeal);
        }
        return true;
    }

    @Override
    public boolean releaseResource(ContainorDataDeal containorDataDeal, Integer status) throws Exception {
        if (status.intValue() == ReleaseStatus.NORMAL.value().intValue()) {
            TransactionContainorBean.dataGainDeals.remove(containorDataDeal.getDefinationEntity().getResourcesId());
            redisOperation.delete(containorDataDeal.getDefinationEntity().getResourcesId());
            this.findGainResource(containorDataDeal);
            containorDataDeal.sendMessages(ReleaseStatus.NORMAL.value().intValue(), "成功释放锁了");
        } else if (status.intValue() == ReleaseStatus.CANCEL.value().intValue()) {
            List<ContainorDataDeal> dataDeals = TransactionContainorBean.dataWaitDeals.get(containorDataDeal.getDefinationEntity().getResourcesId());
            if (dataDeals != null) {
                synchronized (dataDeals) {
                    int index = 0;
                    for (ContainorDataDeal dataDeal : dataDeals) {
                        if (dataDeal.getDefinationEntity().getId().longValue() == containorDataDeal.getDefinationEntity().getId().longValue()) {
                            dataDeals.remove(index);
                            containorDataDeal.sendMessages(LockStatus.CANCEL.value().intValue(),"取消锁成功");
                            break;
                        }
                        index ++;
                    }
                }
            }
            ContainorDataDeal con = TransactionContainorBean.dataGainDeals.get(containorDataDeal.getDefinationEntity().getResourcesId());
            if (con == null) return true;
            TransactionContainorBean.dataGainDeals.remove(containorDataDeal.getDefinationEntity().getResourcesId());
            redisOperation.delete(containorDataDeal.getDefinationEntity().getResourcesId());
            con.sendMessages(ReleaseStatus.CANCEL.value().intValue(), "取消锁成功");
            this.findGainResource(con);
        } else if (status.intValue() == ReleaseStatus.TIMEOUT.value().intValue()) {
            List<ContainorDataDeal> dataDeals = TransactionContainorBean.dataWaitDeals.get(containorDataDeal.getDefinationEntity().getResourcesId());
            if (dataDeals != null) {
                synchronized (dataDeals) {
                    int index = 0;
                    for (ContainorDataDeal dataDeal : dataDeals) {
                        if (dataDeal.getDefinationEntity().getId().longValue() == containorDataDeal.getDefinationEntity().getId().longValue()) {
                            dataDeals.remove(index);
                            containorDataDeal.sendMessages(ReleaseStatus.TIMEOUT.value().intValue(),"请求锁超时");
                            break;
                        }
                        index ++;
                    }
                }
            }
            ContainorDataDeal con = TransactionContainorBean.dataGainDeals.get(containorDataDeal.getDefinationEntity().getResourcesId());
            if (con != null) return true;
        }
        return false;
    }

}
