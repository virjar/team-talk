package com.virjar.tk.server.common;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.checkerframework.checker.units.qual.A;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.data.relational.core.query.Criteria;
import org.springframework.data.relational.core.query.Query;
import reactor.core.publisher.Mono;

import java.util.List;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class PageResult<T> {
    private long total;
    private List<T> records;

    public static <T> Mono<PageResult<T>> selectPag(int pageNo, int pageSize,
                                                    Criteria query, Class<T> clazz,
                                                    R2dbcEntityTemplate template) {
        return selectPag(pageNo, pageSize, Query.query(query), clazz, template);
    }

    public static <T> Mono<PageResult<T>> selectPag(int pageNo, int pageSize,
                                                    Query query, Class<T> clazz,
                                                    R2dbcEntityTemplate template) {
        PageRequest pageRequest = PageRequest.of(pageNo - 1, pageSize);
        Query selector = query.with(pageRequest);
        return template.select(selector, clazz)
                .collectList()
                .zipWith(template.count(query, clazz))
                .map((tuple) -> new PageImpl<>(tuple.getT1(), pageRequest, tuple.getT2())).map((page -> new PageResult<>(page.getTotalElements(), page.getContent())));
    }
}
