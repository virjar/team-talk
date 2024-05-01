package com.virjar.tk.server.service.base.metric.monitor;


import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.BeansException;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.stereotype.Service;

/**
 * 将MeterRegistry注入到全局，以便通过静态api记录指标，
 * 在没有spring环境的时候，本类不生效
 */
@Service
public class MetricsSpringSetter implements ApplicationContextAware {

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        try {
            MeterRegistry meterRegistry = applicationContext.getBean(MeterRegistry.class);
            Monitor.addRegistry(meterRegistry);
        } catch (BeansException ignore) {

        }
    }
}
