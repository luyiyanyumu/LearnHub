package org.dyh.learnhub.common;

import lombok.Data;

import java.util.List;

@Data
public class PageResult<T> {

    private long total;
    private List<T> list;

    public static <T> PageResult<T> of(long total, List<T> list) {
        PageResult<T> r = new PageResult<>();
        r.total = total;
        r.list = list;
        return r;
    }
}
