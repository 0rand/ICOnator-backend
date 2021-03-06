package io.iconator.rates.task;

import io.iconator.rates.config.RatesAppConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.Trigger;
import org.springframework.scheduling.TriggerContext;
import org.springframework.stereotype.Component;

import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;

@Component
public class FetchRatesTrigger implements Trigger {

    @Autowired
    private RatesAppConfig ratesAppConfig;

    @Override
    public Date nextExecutionTime(TriggerContext triggerContext) {
        Calendar nextExecutionTime = new GregorianCalendar();
        Date lastActualExecutionTime = triggerContext.lastActualExecutionTime();
        nextExecutionTime.setTime(lastActualExecutionTime != null ? lastActualExecutionTime : new Date());
        nextExecutionTime.add(Calendar.MILLISECOND, ratesAppConfig.getPeriodicInterval());
        return nextExecutionTime.getTime();
    }

}
