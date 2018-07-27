package cn.hurrican.aspect;

import cn.hurrican.annotation.HashField;
import cn.hurrican.annotation.KeyParam;
import cn.hurrican.annotation.ListIndex;
import cn.hurrican.annotation.ReadCache;
import cn.hurrican.config.CacheBean;
import cn.hurrican.config.CacheConstant;
import cn.hurrican.config.KeyType;
import cn.hurrican.redis.RedisExecutor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * @Author: Hurrican
 * @Description:
 * @Date 2018/7/27
 * @Modified 9:27
 */
@Aspect
@Component
public class ReadCacheAspect {

    private static Logger logger = LogManager.getLogger(ReadCacheAspect.class);

    @Autowired
    private RedisExecutor executor;

    @Pointcut("@annotation(cn.hurrican.annotation.ReadCache)")
    public void readValueFromCache() {

    }

    @Around(value = "readValueFromCache() && @annotation(args)")
    public Object readCache(ProceedingJoinPoint joinPoint, ReadCache args) {
        String methodInvokeName = joinPoint.getSignature().toLongString();
        Object execResult = null;
        List<Method> itemMethod = Arrays.stream(joinPoint.getTarget().getClass().getDeclaredMethods())
                .filter(e -> e.toString().equals(methodInvokeName))
                .collect(Collectors.toList());
        if (itemMethod.size() > 0) {
            Method method = itemMethod.get(0);
            CacheBean cacheBean = CacheBean.build();
            cacheBean.setType(args.valueClazz());
            String key = args.prefixKey() + args.postfixKey();
            Class<?> returnType = method.getReturnType();
            Annotation[][] parameterAnnotations = method.getParameterAnnotations();
            Object[] params = joinPoint.getArgs();
            for (int i = 0; i < parameterAnnotations.length; i++) {
                for (int j = 0; j < parameterAnnotations[i].length; j++) {
                    Annotation annotation = parameterAnnotations[i][j];
                    if (annotation.annotationType().equals(KeyParam.class)) {
                        key = key.replace(((KeyParam) annotation).value(), params[i].toString());
                    } else if (annotation.annotationType().equals(ListIndex.class)) {
                        int indexType = ((ListIndex) annotation).lindexType();
                        if(indexType == CacheConstant.LEFT_INDEX){
                            cacheBean.setLindex((Integer) params[i]);
                        }else{
                            cacheBean.setRindex((Integer) params[i]);
                        }
                    } else if (annotation.annotationType().equals(HashField.class)) {
                        cacheBean.setField(params[i].toString());
                    }
                }
            }

            if (isCollectionType(returnType)) {
                switch (args.type()) {
                    case KeyType.LIST:
                        execResult = readListFromCache(cacheBean, key);
                        break;
                    default:
                        break;
                }
            } else {

            }
        }


        try {
            execResult = joinPoint.proceed();
        } catch (Throwable throwable) {
            throwable.printStackTrace();
        }

        return execResult;

    }

    private Object readListFromCache(CacheBean cacheBean, String key) {
        return executor.doInRedis(instance -> {
            ArrayList<Object> objects = new ArrayList<>();
            ObjectMapper mapper = new ObjectMapper();

            if(cacheBean.getLindex() != null && cacheBean.getRindex() != null){
                List<String> jsonList = instance.lrange(key, cacheBean.getLindex(), cacheBean.getRindex());
                jsonList.forEach(ele -> {
                    try {
                        objects.add(mapper.readValue(ele, cacheBean.getType()));
                    } catch (IOException e) {
                        e.printStackTrace();
                        logger.error(e);
                    }
                });
            }else{
                Integer index = Optional.ofNullable(cacheBean.getLindex()).orElse(cacheBean.getRindex());
                if(index == null){
                    throw new RuntimeException("查询list存储结构必须指定索引号！");
                }
                String json = instance.lindex(key, index);
                objects.add(mapper.readValue(json, cacheBean.getType()));
            }
            return objects;
        });
    }


    /**
     * 判断一个类是否实现了 Collection 接口
     * @param clazz
     * @return
     */
    public boolean isCollectionType(Class clazz) {
        if (clazz == null) {
            return false;
        }
        if (clazz.equals(Collection.class)) {
            return true;
        }
        return Arrays.stream(clazz.getInterfaces()).collect(Collectors.toSet()).contains(Collection.class);
    }
}