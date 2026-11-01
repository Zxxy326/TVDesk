package com.github.tvbox.osc.bean;

import com.thoughtworks.xstream.annotations.XStreamAlias;
import com.thoughtworks.xstream.annotations.XStreamAsAttribute;
import com.thoughtworks.xstream.annotations.XStreamConverter;
import com.thoughtworks.xstream.annotations.XStreamImplicit;
import com.thoughtworks.xstream.converters.extended.ToAttributedValueConverter;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * @author pj567
 * @date :2020/12/18
 * @description:
 */
@XStreamAlias("class")
public class MovieSort implements Serializable {
    @XStreamImplicit(itemFieldName = "ty")
    public List<SortData> sortList;

    @XStreamAlias("ty")
    @XStreamConverter(value = ToAttributedValueConverter.class, strings = {"name"})
    public static class SortData implements Serializable, Comparable<SortData> {
        @XStreamAsAttribute
        public String id;
        public String name;
        public int sort = -1;
        public boolean select = false;
        /**
         * 这个类目带的筛选条件。
         *
         * <p>源把筛选放在<b>类目列表之外</b>（顶层 {@code filters}，key 是类目 id），
         * 解析完类目之后要按 id 分发到各个 {@code SortData} 上 ——
         * 见 {@code SourceFeed.applyFilters}。这里只负责存。
         */
        public ArrayList<SortFilter> filters = new ArrayList<>();
        /** 已经选中的筛选：filter 的 key → value */
        public HashMap<String, String> filterSelect = new HashMap<>();
        public String flag; // 类型

        public SortData() {
        }

        public SortData(String id, String name) {
            this.id = id;
            this.name = name;
        }

        public int filterSelectCount() {
            if (filterSelect == null) {
                return 0;
            }
            int count = 0;
            for (String filter : filterSelect.values()) {
                if (filter != null && !filter.isEmpty()) {
                    count++;
                }
            }
            return count;
        }

        @Override
        public int compareTo(SortData o) {
            return this.sort - o.sort;
        }

        @Override
        public String toString() {
            return "SortData{" +
                    "id='" + id + '\'' +
                    ", name='" + name + '\'' +
                    ", sort=" + sort +
                    ", select=" + select +
                    ", filters=" + filters +
                    ", filterSelect=" + filterSelect +
                    ", flag='" + flag + '\'' +
                    '}';
        }
    }

    /**
     * 一个筛选维度（比如「年份」「地区」）。
     *
     * <p>值是一个 map：显示名 → 传给源站的参数值。比如
     * {@code {"2026": "2026", "全部": ""}} —— 显示「全部」时传空串。
     */
    public static class SortFilter {
        public String key;
        public String name;
        public LinkedHashMap<String, String> values = new LinkedHashMap<>();

        @Override
        public String toString() {
            return "SortFilter{" +
                    "key='" + key + '\'' +
                    ", name='" + name + '\'' +
                    ", values=" + values +
                    '}';
        }
    }

}