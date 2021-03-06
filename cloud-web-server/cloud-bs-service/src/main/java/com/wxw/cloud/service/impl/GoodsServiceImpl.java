package com.wxw.cloud.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.wxw.cloud.bo.SkuBO;
import com.wxw.cloud.bo.SpuBO;
import com.wxw.cloud.dao.*;
import com.wxw.cloud.domain.*;
import com.wxw.cloud.result.PageResult;
import com.wxw.cloud.service.ICategoryService;
import com.wxw.cloud.service.IGoodsService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.wxw.cloud.tools.ListPageUtil;
import com.wxw.cloud.vo.SkuVO;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.AmqpTemplate;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * <p>
 *  商品管理 实现类
 * </p>
 *
 * @author WXW
 * @since 2020-04-04
 */
@Slf4j
@Service
public class GoodsServiceImpl implements IGoodsService {

    @Resource
    private SpuMapper spuMapper;

    @Resource
    private SpuDetailMapper spuDetailMapper;

    @Resource
    private BrandMapper brandMapper;

    @Resource
    private ICategoryService categoryService;

    @Resource
    private SkuMapper skuMapper;

    @Resource
    private StockMapper stockMapper;

    @Resource
    private AmqpTemplate amqpTemplate;

    /**
     * 根据条件分页查询SPU
     */
    @Override
    public PageResult<SpuBO> querySpuByPage(String key, Boolean saleable, Integer page, Integer rows) {

        QueryWrapper<Spu> wrapper = new QueryWrapper<>();
        // 添加查询条件
        if (StringUtils.isNotBlank(key)){
            wrapper.like("title", key);
        }
        // 添加上下架的过滤条件
        if(saleable != null){
            wrapper.eq("saleable", saleable);
        }
        // 添加分页条件
        Page<Spu> pageParam = new Page<>(page,rows);
        // 执行查询获取SPU集合
        Page<Spu> spuPage = this.spuMapper.selectPage(pageParam, wrapper);
        List<Spu> spus = spuPage.getRecords();
        // SPU集合转化为SpuBO的集合
        List<SpuBO> spuBOs = spus.stream().map(spu -> {
            SpuBO spuBO = new SpuBO();
            BeanUtils.copyProperties(spu, spuBO);
            // 查询分类名称
            List<String> names = this.categoryService.queryNamesByIds(Arrays.asList(spu.getCid1(), spu.getCid2(), spu.getCid3()));
            spuBO.setCname(StringUtils.join(names, "-"));
            Brand brand = this.brandMapper.selectById(spu.getBrandId());
            // 查询品牌名称
            spuBO.setBname(brand.getName());
            return spuBO;
        }).collect(Collectors.toList());
        // 返回PageResult<SpuBO>
        return new PageResult<>(spuPage.getTotal(),spuBOs);
    }

    /**
     *  新增商品
     * @param spuBO
     */
    @Transactional
    @Override
    public void saveGoods(SpuBO spuBO) {

        // 新增spu
        spuBO.setId(null);
        spuBO.setSaleable(true);
        spuBO.setValid(true);
        spuBO.setCreateTime(LocalDateTime.now());
        spuBO.setLastUpdateTime(spuBO.getCreateTime());
        this.spuMapper.insert(spuBO);

        // 新增spuDetail
        SpuDetail spuDetail = spuBO.getSpuDetail();
        spuDetail.setSpuId(spuBO.getId());
        this.spuDetailMapper.insert(spuDetail);
        // 新增sku and stock
        this.saveSkuAndStock(spuBO);

        // 发送消息
        sendMsg("insert",spuBO.getId());
    }

    /**
     *  MQ 监听发送消息
     * @param type
     * @param id
     */
    private void sendMsg(String type,Long id) {
        try {
            this.amqpTemplate.convertAndSend("item."+type,id);
        } catch (AmqpException e) {
            log.info("新增商品消息发送失败：=>{}");
        }
    }

    // 新增sku and stock
    private void saveSkuAndStock(SpuBO spuBO) {
        spuBO.getSkus().forEach(sku -> {
            // 新增sku
            sku.setId(null);
            sku.setSpuId(spuBO.getId());
            sku.setCreateTime(LocalDateTime.now());
            sku.setLastUpdateTime(sku.getCreateTime());
            this.skuMapper.insert(sku);
            // 新增stock
            Stock stock = new Stock();
            stock.setSkuId(sku.getId());
            stock.setStock(sku.getStock());
            this.stockMapper.insert(stock);

        });
    }

    /**
     * 根据spuId查询SpuDetail信息
     */
    @Override
    public SpuDetail querySpuDetailBySpuId(Long spuId) {
        return this.spuDetailMapper.selectById(spuId);
    }

    @Override
    public List<Sku> querySkusBySpuId(Long spuId) {
       QueryWrapper<Sku> wrapper = new QueryWrapper<>();
       wrapper.eq("spu_id", spuId);
        List<Sku> skus = this.skuMapper.selectList(wrapper);
        skus.forEach(sku -> {
            Stock stock = this.stockMapper.selectById(sku.getId());
            if(stock != null){
                sku.setStock(stock.getStock());
            }
        });
        return skus;
    }

    /**
     * 修改商品信息
     * @param spuBO
     */
    @Transactional
    @Override
    public void updateGoods(SpuBO spuBO) {

        // 根据spuId 查询要删除的sku
        QueryWrapper<Sku> wrapper = new QueryWrapper<>();
        wrapper.eq("spu_id", spuBO.getId());
        List<Sku> skus = this.skuMapper.selectList(wrapper);
        skus.forEach(sku -> {
            // 删除 stock
            this.stockMapper.deleteById(sku.getId());
        });

        // 删除sku
        QueryWrapper<Sku> wrapper1 = new QueryWrapper<>();
        wrapper1.eq("spu_id", spuBO.getId());
        this.skuMapper.delete(wrapper1);

        // 新增sku and stock
        this.saveSkuAndStock(spuBO);
        // 更新 spu和spuDetail
        spuBO.setCreateTime(null);
        spuBO.setLastUpdateTime(LocalDateTime.now());
        spuBO.setValid(null);
        spuBO.setSaleable(null);
        this.spuMapper.updateById(spuBO);
        this.spuDetailMapper.updateById(spuBO.getSpuDetail());
        sendMsg("update", spuBO.getId());
    }

    @Override
    public Spu querySpuById(Long id) {
        return this.spuMapper.selectById(id);
    }

    @Override
    public Sku querySkuBySkuId(Long skuId) {
        return this.skuMapper.selectById(skuId);
    }

    @Override
    public SkuBO getSkusAndCid3(Integer page,Integer rows) {
        SkuBO skuBO = new SkuBO();
        //1.查询SPU
        QueryWrapper<Spu> querySpu = new QueryWrapper<>();
        List<Spu> spus = this.spuMapper.selectList(querySpu);
        //2.通过spu查询skulist
        PageResult<SkuVO> pageResult = new PageResult<>();
        // 商品集合
        List<SkuVO> skus = new ArrayList<>();
        List<Long> cid3s= new ArrayList<>();
        //log.info("SPU：{}",spus);
        spus.forEach(spu -> {
            QueryWrapper<Sku> querySku = new QueryWrapper<>();
            querySku.eq("spu_id", spu.getId());
            List<Sku> list = this.skuMapper.selectList(querySku);
            //log.info("查询出的库存数量：{}",list);
            list.forEach(sku -> {
//                log.info("SKU ：{}",sku);
//                log.info("SKU ID：{}",sku.getId());
                Stock stock = this.stockMapper.selectById(sku.getId());

                if (stock != null){
                    sku.setStock(stock.getStock());
                }
                // 设置空库存
//                Stock add = new Stock();
//                add.setSkuId(sku.getId());
//                add.setStock(100);
//                this.stockMapper.updateById(add);
//                sku.setStock(add.getStock());
            });
            // sku 集合转换为 skuvo集合
            List<SkuVO> skuVOS = list.stream().map(sku -> {
                SkuVO skuVO = new SkuVO();
                skuVO.setSubtitle(spu.getSubTitle());
                skuVO.setStock(sku.getStock());
                skuVO.setId(sku.getId());
                skuVO.setSpuId(sku.getSpuId());
                skuVO.setImages(sku.getImages());
                skuVO.setPrice(sku.getPrice());
                skuVO.setTitle(sku.getTitle());
                return skuVO;
            }).collect(Collectors.toList());
            skus.addAll(skuVOS);
            cid3s.add(spu.getCid3());
        });
        // 分页处理
        ListPageUtil<SkuVO> pager = new ListPageUtil<>(skus,rows);
        List<SkuVO> pagedList = pager.getPagedList(page);
        pageResult.setItems(pagedList);
        pageResult.setTotal(new Long(skus.size()));
        pageResult.setTotalPage(new Long(pager.getPageCount()));
        skuBO.setSkus(pageResult);
        //3. 增加目录信息
        skuBO.setCid3s(new HashSet<>(cid3s));
        List<String> names = this.categoryService.queryNamesByIds(cid3s);
        skuBO.setCname(new HashSet<>(names));
        // 保存数据
        return skuBO;
    }



}
