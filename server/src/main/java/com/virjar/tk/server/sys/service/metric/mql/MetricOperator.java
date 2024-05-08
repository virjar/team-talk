package com.virjar.tk.server.sys.service.metric.mql;

import com.virjar.tk.server.sys.service.metric.MetricVo;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;

import java.util.*;
import java.util.function.BinaryOperator;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 实现指标的加减乘除四则运算
 * add/sub/divide/multiply
 */
public class MetricOperator implements Function<Context, Object> {
    private final Function<Context, Object> leftParam;
    private final Function<Context, Object> rightParam;
    private final BinaryOperator<Double> operator;

    public MetricOperator(Function<Context, Object> leftParam,
                          Function<Context, Object> rightParam,
                          BinaryOperator<Double> operator) {
        this.leftParam = leftParam;
        this.rightParam = rightParam;
        this.operator = operator;
    }

    public static MetricOperator add(Function<Context, Object> leftParam, Function<Context, Object> rightParam) {
        return new MetricOperator(leftParam, rightParam, Double::sum);
    }

    public static MetricOperator minus(Function<Context, Object> leftParam, Function<Context, Object> rightParam) {
        return new MetricOperator(leftParam, rightParam, (a, b) -> a - b);
    }

    public static MetricOperator multiply(Function<Context, Object> leftParam, Function<Context, Object> rightParam) {
        return new MetricOperator(leftParam, rightParam, (a, b) -> a * b);
    }

    public static MetricOperator divide(Function<Context, Object> leftParam, Function<Context, Object> rightParam) {
        return new MetricOperator(leftParam, rightParam, (a, b) -> {
            if (a.compareTo(0D) == 0) {
                return 0D;
            }
            if (b.isNaN() || b.isInfinite() || b.compareTo(0D) == 0) {
                return 0D;
            }
            return a / b;
        });
    }

    @Override
    public Object apply(Context context) {
        Object left = checkParam(leftParam.apply(context));
        Object right = checkParam(rightParam.apply(context));

        if (left instanceof Number && right instanceof Number) {
            return doCalculate(((Number) left).doubleValue(), ((Number) right).doubleValue());
        }

        if (left instanceof Context.MQLVar && right instanceof Context.MQLVar) {
            return calcMQLVar((Context.MQLVar) left, (Context.MQLVar) right);
        }

        return calcMQLVarAndNumber(left, right);
    }


    private Context.MQLVar calcMQLVarAndNumber(Object left, Object right) {
        boolean leftNum;
        double doubleValue;
        Context.MQLVar mqlVar;

        if (left instanceof Number) {
            leftNum = true;
            doubleValue = ((Number) left).doubleValue();
            mqlVar = (Context.MQLVar) right;
        } else {
            leftNum = false;
            doubleValue = ((Number) right).doubleValue();
            mqlVar = (Context.MQLVar) left;
        }

        TreeMap<String, List<MetricVo>> data = Maps.newTreeMap();
        mqlVar.data.forEach((s, metricVos) -> data.put(s, metricVos.stream()
                .map(metricVo -> {
                    Double ret = doCalculate(
                            leftNum ? doubleValue : metricVo.getValue(),
                            leftNum ? metricVo.getValue() : doubleValue
                    );
                    if (ret == null || ret.isNaN()) {
                        return null;
                    }
                    MetricVo retMetricVo = MetricVo.cloneMetricVo(metricVo);
                    retMetricVo.setValue(ret);
                    return retMetricVo;
                }).filter(Objects::nonNull)
                .collect(Collectors.toList())));
        return Context.MQLVar.newVar(data);
    }

    private Context.MQLVar calcMQLVar(Context.MQLVar left, Context.MQLVar right) {
        TreeMap<String, List<MetricVo>> leftData = left.data;
        TreeMap<String, List<MetricVo>> rightData = right.data;

        TreeSet<String> timeKeys = Sets.newTreeSet();
        timeKeys.addAll(leftData.keySet());
        timeKeys.addAll(rightData.keySet());

        TreeMap<String, List<MetricVo>> ret = Maps.newTreeMap();

        timeKeys.forEach(timeKey -> {
            List<MetricVo> leftMetricVos = leftData.getOrDefault(timeKey, Collections.emptyList());
            List<MetricVo> rightMetricVos = rightData.getOrDefault(timeKey, Collections.emptyList());

            Map<String, MetricVo> leftByGroup = leftMetricVos.stream().collect(Collectors.toMap(MetricVo::toTagId, i -> i));
            Map<String, MetricVo> rightByGroup = rightMetricVos.stream().collect(Collectors.toMap(MetricVo::toTagId, i -> i));

            List<MetricVo> calcPoint = Lists.newArrayList();
            ret.put(timeKey, calcPoint);

            HashSet<String> union = new HashSet<>(leftByGroup.keySet());
            union.retainAll(rightByGroup.keySet());

            union.forEach(unionTag -> {
                MetricVo leftMetricVo = leftByGroup.get(unionTag);
                MetricVo rightMetricVo = rightByGroup.get(unionTag);

                MetricVo metricVo = MetricVo.cloneMetricVo(leftMetricVo);
                metricVo.setValue(doCalculate(leftMetricVo.getValue(), rightMetricVo.getValue()));
                calcPoint.add(metricVo);

                leftMetricVos.remove(leftMetricVo);
                rightMetricVos.remove(rightMetricVo);
            });

            for (MetricVo leftRemain : leftMetricVos) {
                MetricVo metricVo = MetricVo.cloneMetricVo(leftRemain);
                metricVo.setValue(doCalculate(leftRemain.getValue(), 0d));
                calcPoint.add(metricVo);
            }

            for (MetricVo rightRemain : rightMetricVos) {
                MetricVo metricVo = MetricVo.cloneMetricVo(rightRemain);
                metricVo.setValue(doCalculate(0d, rightRemain.getValue()));
                calcPoint.add(metricVo);
            }

        });

        return Context.MQLVar.newVar(ret);
    }

    public Double doCalculate(Double left, Double right) {
        if (left == null || right == null) {
            return null;
        }
        return operator.apply(left, right);
    }

    private Object checkParam(Object param) {
        if (param == null) {
            throw new IllegalStateException("empty param");
        }
        if (!(param instanceof Number) && !(param instanceof Context.MQLVar)) {
            throw new IllegalStateException("AlgorithmUnit param must be number of TreeMap<String, List<MetricVo>> ");
        }
        return param;
    }
}
