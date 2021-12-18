package nmj.util;

import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.text.Collator;
import java.util.*;
import java.util.function.*;

/**
 * 非空设计|非线程安全|有序|单线程|非懒加载|的流式处理工具类
 *
 * @param <T>
 * @author nmj
 */
public final class Table<T> implements Iterable<T> {

    private static final Table EMPTY_TABLE = new Table<>(Collections.emptyList());

    private static Method SPRING_COPY_METHOD = null;
    static {
        try {
            Class<?> beanUtils = Class.forName("org.springframework.beans.BeanUtils");
            SPRING_COPY_METHOD = beanUtils.getDeclaredMethod("copyProperties",
                    Object.class, Object.class, Class.class, String[].class);
            SPRING_COPY_METHOD.setAccessible(true);
        } catch (Throwable t) {
            System.err.println("class org.springframework.beans.BeanUtils not found! nmj.Table.mapList(java.lang.Class<E>, java.lang.String...) not be working");
        }
    }

    private final Iterable<T> data;

    @Override
    public Iterator<T> iterator() {
        return new NonNullIterator<>(data.iterator());
    }

    @SafeVarargs
    public static <T> Table<T> of(T... elements) {
        if (elements == null || elements.length == 0) {
            return EMPTY_TABLE;
        }
        return new Table<>(Arrays.asList(elements));
    }

    public static <T> Table<T> of(Iterable<T> elements) {
        if (elements == null) {
            return (Table<T>) EMPTY_TABLE;
        }
        return new Table<>(elements);
    }

    /**
     * 使用StringTokenizer对字符串进行分割, 并去除空元素后构造成Table
     *
     * @param str
     * @param delimiters
     * @return
     */
    public static Table<String> ofSplit(String str, String delimiters) {
        if (str == null || str.isEmpty() || str.trim().isEmpty()) {
            return EMPTY_TABLE;
        }
        if (delimiters == null || delimiters.isEmpty() || delimiters.trim().isEmpty()) {
            throw new IllegalArgumentException("delimiters cannot be blank");
        }
        StringTokenizer st = new StringTokenizer(str, delimiters);
        List<String> tokens = new ArrayList<>();
        while (st.hasMoreTokens()) {
            String token = st.nextToken().trim();
            if (!token.isEmpty()) {
                tokens.add(token);
            }
        }
        return new Table<>(tokens);
    }

    /**
     * 递归地将elements中类型为clazz或者其子类的所有元素找出来构造成Table
     *
     * @param clazz
     * @param elements
     * @param <T>
     * @return
     */
    public static <T> Table<T> ofClass(Class<T> clazz, Object... elements) {
        if (clazz == null) {
            throw new IllegalArgumentException("clazz cannot be null");
        }
        if (elements == null || elements.length == 0) {
            return EMPTY_TABLE;
        }
        Set<Object> visitedElements = new HashSet<>();
        List<T> data = new ArrayList<>();
        for (Object element : elements) {
            ofClass(data, clazz, element, visitedElements);
        }
        return new Table<>(data);
    }

    public Table<T> concat(Table<T> newTable) {
        if (newTable == null || newTable.isEmpty()) {
            return this;
        }
        List<T> list =  new ArrayList<>(estimateSize() + newTable.estimateSize());
        for (T t : this) {
            list.add(t);
        }
        for (T t : newTable) {
            list.add(t);
        }
        return of(list);
    }

    @SafeVarargs
    public final Table<T> concat(T... elements) {
        Table<T> newTable = of(elements);
        return concat(newTable);
    }

    public Table<T> concat(Iterable<T> elements) {
        Table<T> newTable = of(elements);
        return concat(newTable);
    }

    public Table<T> concatOfClass(Class<T> clazz, Object... elements) {
        Table<T> newTable = ofClass(clazz, elements);
        return concat(newTable);
    }

    public boolean nonEmpty() {
        return this.iterator().hasNext();
    }

    public boolean isEmpty() {
        return !nonEmpty();
    }

    public boolean exists(Predicate<T> predicate) {
        for (T t : this) {
            if (predicate.test(t)) {
                return true;
            }
        }
        return false;
    }

    public boolean notExist(Predicate<T> predicate) {
        for (T t : this) {
            if (!predicate.test(t)) {
                return true;
            }
        }
        return false;
    }

    public int count() {
        int index = 0;
        for (T t : this) {
            index++;
        }
        return index;
    }

    public int count(Predicate<T> predicate) {
        int index = 0;
        for (T t : this) {
            if (predicate.test(t)) {
                index++;
            }
        }
        return index;
    }

    public Table<T> each(Consumer<T> consumer) {
        for (T t : this) {
            consumer.accept(t);
        }
        return this;
    }

    /**
     * 带下标的遍历(下标从0开始)
     *
     * @param biConsumer
     * @return
     */
    public Table<T> each(BiConsumer<T, Integer> biConsumer) {
        int index = 0;
        for (T t : this) {
            biConsumer.accept(t, index);
            index++;
        }
        return this;
    }

    /**
     * 分组遍历
     *
     * @param groupSize
     * @param consumer
     * @return
     */
    public Table<T> each(int groupSize, Consumer<Table<T>> consumer) {
        if (groupSize < 1) {
            throw new IllegalArgumentException();
        }
        List<T> group = new ArrayList<>(groupSize);
        for (T t : this) {
            group.add(t);
            if (group.size() >= groupSize) {
                consumer.accept(of(group));
                group.clear();
            }
        }
        if (!group.isEmpty()) {
            consumer.accept(of(group));
            group.clear();
        }
        return this;
    }

    /**
     * 二级遍历 注意biConsumer中两个元素均不会为null
     *
     * @param level2
     * @param biConsumer
     * @param <E>
     * @return
     */
    public <E> Table<T> each(Function<T, Iterable<E>> level2, BiConsumer<T, E> biConsumer) {
        for (T t : this) {
            Iterable<E> l2 = level2.apply(t);
            if (l2 != null) {
                for (E e : l2) {
                    if (e != null) {
                        biConsumer.accept(t, e);
                    }
                }
            }
        }
        return this;
    }

    public List<T> list() {
        List<T> list = new ArrayList<>(estimateSize());
        for (T t : this) {
            list.add(t);
        }
        return list;
    }

    public Set<T> set() {
        Set<T> set = new LinkedHashSet<>(estimateSize());
        for (T t : this) {
            set.add(t);
        }
        return set;
    }

    public Table<T[]> array() {
        List<T> list = list();
        if (list.isEmpty()) {
            return EMPTY_TABLE;
        }
        T[] array = (T[]) Array.newInstance(list.get(0).getClass(), list.size());
        list.toArray(array);
        return of(Collections.singletonList(array));
    }

    public Table<T> distinct() {
        return of(set());
    }

    public <E> Table<T> distinct(Function<T, E> function) {
        Map<E, T> distinct = new LinkedHashMap<>(estimateSize() * 2);
        for (T t : this) {
            E e = function.apply(t);
            distinct.putIfAbsent(e, t);
        }
        return of(distinct.values());
    }

    public Table<T> head() {
        for (T t : this) {
            return of(t);
        }
        return EMPTY_TABLE;
    }

    public T headOrElse(T orElse) {
        for (T t : this) {
            return t;
        }
        return orElse;
    }

    public T headOrNull() {
        return headOrElse(null);
    }

    public Table<T> head(Predicate<T> predicate) {
        for (T t : this) {
            if (predicate.test(t)) {
                return of(t);
            }
        }
        return EMPTY_TABLE;
    }

    public T headOrElse(Predicate<T> predicate, T orElse) {
        for (T t : this) {
            if (predicate.test(t)) {
                return t;
            }
        }
        return orElse;
    }

    public <E> Table<E> head(Function<T, E> function) {
        for (T t : this) {
            E e = function.apply(t);
            if (e != null) {
                return of(e);
            }
        }
        return EMPTY_TABLE;
    }

    public <E> E headOrElse(Function<T, E> function, E orElse) {
        for (T t : this) {
            E e = function.apply(t);
            if (e != null) {
                return e;
            }
        }
        return orElse;
    }

    public T headOrNull(Predicate<T> predicate) {
        return headOrElse(predicate, null);
    }

    public <E> E headOrNull(Function<T, E> function) {
        for (T t : this) {
            E e = function.apply(t);
            if (e != null) {
                return e;
            }
        }
        return null;
    }

    public Table<T> first() {
        return head();
    }

    public T firstOrNull() {
        return headOrNull();
    }

    public Table<T> first(Predicate<T> predicate) {
        return head(predicate);
    }

    public T firstOrNull(Predicate<T> predicate) {
        return headOrElse(predicate, null);
    }

    public <E> E firstOrElse(Function<T, E> function) {
        return headOrElse(function, null);
    }

    public <E> E firstOrNull(Function<T, E> function) {
        return headOrNull(function);
    }

    public Table<T> last() {
        T last = null;
        for (T t : this) {
            last = t;
        }
        return of(last);
    }

    public T lastOrElse(T orElse) {
        T last = orElse;
        for (T t : this) {
            last = t;
        }
        return last;
    }

    public T lastOrNull() {
        T last = null;
        for (T t : this) {
            last = t;
        }
        return last;
    }

    public Table<T> last(Predicate<T> predicate) {
        T last = null;
        for (T t : this) {
            if (predicate.test(t)) {
                last = t;
            }
        }
        return of(last);
    }

    public <E> Table<E> last(Function<T, E> function) {
        E last = null;
        for (T t : this) {
            E e = function.apply(t);
            if (e != null) {
                last = e;
            }
        }
        return of(last);
    }

    public T lastOrElse(Predicate<T> predicate, T orElse) {
        T last = orElse;
        for (T t : this) {
            if (predicate.test(t)) {
                last = t;
            }
        }
        return last;
    }

    public <E> E lastOrElse(Function<T, E> function, E orElse) {
        E last = orElse;
        for (T t : this) {
            E e = function.apply(t);
            if (e != null) {
                last = e;
            }
        }
        return last;
    }

    public T lastOrNull(Predicate<T> predicate) {
        T last = null;
        for (T t : this) {
            if (predicate.test(t)) {
                last = t;
            }
        }
        return last;
    }

    public <E> E lastOrNull(Function<T, E> function) {
        E last = null;
        for (T t : this) {
            E e = function.apply(t);
            if (e != null) {
                last = e;
            }
        }
        return last;
    }


    public Table<T> filter(Predicate<T> predicate) {
        return all(predicate);
    }

    public Table<T> all(Predicate<T> predicate) {
        return of(list(predicate));
    }

    public Table<T> allNot(Predicate<T> predicate) {
        return of(listNot(predicate));
    }

    public <E> Table<E> map(Function<T, E> function) {
        return of(mapList(function));
    }

    /**
     * 利用spring的beanUtils进行对象拷贝
     * @param supplier
     * @param ignoreProperties
     * @param <E>
     * @return
     */
    public <E> Table<E> map(Supplier<E> supplier, String... ignoreProperties) {
        return of(mapList(supplier, ignoreProperties));
    }

    public <E> Table<E> flatMap(Function<T, Iterable<E>> function) {
        return of(flatMapList(function));
    }

    public <E> Table<E> flatMap(int groupSize, Function<Table<T>, Iterable<E>> function) {
        return of(flatMapList(groupSize, function));
    }

    public List<T> list(Predicate<T> predicate) {
        List<T> list = new ArrayList<>(estimateSize());
        for (T t : this) {
            if (predicate.test(t)) {
                list.add(t);
            }
        }
        return list;
    }

    public List<T> listNot(Predicate<T> predicate) {
        List<T> list = new ArrayList<>(estimateSize());
        for (T t : this) {
            if (!predicate.test(t)) {
                list.add(t);
            }
        }
        return list;
    }

    public <E> List<E> mapList(Function<T, E> function) {
        List<E> list = new ArrayList<>(estimateSize());
        for (T t : this) {
            E e = function.apply(t);
            if (e != null) {
                list.add(e);
            }
        }
        return list;
    }

    /**
     * 利用spring的beanUtils进行对象拷贝
     * @param supplier
     * @param ignoreProperties
     * @param <E>
     * @return
     */
    public <E> List<E> mapList(Supplier<E> supplier, String... ignoreProperties) {
        if (SPRING_COPY_METHOD == null) {
            throw new IllegalStateException("class org.springframework.beans.BeanUtils not found!");
        }
        return mapList(t -> {
            E e = supplier.get();
            if (e == null) {
                throw new IllegalArgumentException();
            }
            try {
                SPRING_COPY_METHOD.invoke(null, t, e, null, ignoreProperties);
                return e;
            } catch (Exception ex) {
                throw new RuntimeException(ex);
            }
        });
    }

    public <E> Set<E> mapSet(Function<T, E> function) {
        Set<E> set = new LinkedHashSet<>(estimateSize());
        for (T t : this) {
            E e = function.apply(t);
            if (e != null) {
                set.add(e);
            }
        }
        return set;
    }

    public <E> List<E> flatMapList(Function<T, Iterable<E>> function) {
        List<E> list = new ArrayList<>(estimateSize() * 8);
        for (T t : this) {
            Iterable<E> ie = function.apply(t);
            if (ie != null) {
                for (E e : ie) {
                    if (e != null) {
                        list.add(e);
                    }
                }
            }
        }
        return list;
    }

    public <E> List<E> flatMapList(int groupSize, Function<Table<T>, Iterable<E>> function) {
        if (groupSize < 1) {
            throw new IllegalArgumentException();
        }
        List<E> list = new ArrayList<>(estimateSize() * 8);
        List<T> group = new ArrayList<>(groupSize);
        for (T t : this) {
            group.add(t);
            if (group.size() >= groupSize) {
                Iterable<E> ie = function.apply(of(group));
                group.clear();
                for (E e : of(ie)) {
                    list.add(e);
                }
            }
        }
        if (!group.isEmpty()) {
            Iterable<E> ie = function.apply(of(group));
            group.clear();
            for (E e : of(ie)) {
                list.add(e);
            }
        }
        return list;
    }

    public <E> Set<E> flatMapSet(Function<T, Iterable<E>> function) {
        Set<E> set = new LinkedHashSet<>(estimateSize() * 8);
        for (T t : this) {
            Iterable<E> ie = function.apply(t);
            if (ie != null) {
                for (E e : ie) {
                    if (e != null) {
                        set.add(e);
                    }
                }
            }
        }
        return set;
    }

    /**
     * 将元素(过滤掉空+trim后)拼接成一个字符串
     *
     * @param estimateLength 预估的总拼接长度
     * @param open
     * @param delimiters
     * @param close
     * @param function
     * @return
     */
    public String join(int estimateLength, String open, String delimiters, String close, Function<T, CharSequence> function) {
        if (estimateLength <= 0) {
            estimateLength = 128;
        }
        StringBuilder sb = new StringBuilder(estimateLength);
        if (open != null) {
            sb.append(open);
        }
        boolean firstAppendHasSuccess = false;
        for (T t : this) {
            CharSequence cs = function.apply(t);
            boolean success = appendTextWithTrim(sb, cs, delimiters, firstAppendHasSuccess);
            if (success) {
                firstAppendHasSuccess = true;
            }
        }
        if (close != null) {
            sb.append(close);
        }
        return sb.toString();
    }

    public String join(String open, String delimiters, String close, Function<T, CharSequence> function) {
        return join(128, open, delimiters, close, function);
    }

    public String join(String delimiters, Function<T, CharSequence> function) {
        return join(128, null, delimiters, null, function);
    }

    public String join(String open, String delimiters, String close) {
        return join(128, open, delimiters, close, Objects::toString);
    }

    public String join(String delimiters) {
        return join(128, null, delimiters, null, Objects::toString);
    }

    public <E> Map<E, Table<T>> groupBy(boolean removeNullKey, Function<T, E> function) {
        Map<E, List<T>> map = new LinkedHashMap<>(estimateSize() * 2);
        for (T t : this) {
            E e = function.apply(t);
            if (e == null && removeNullKey) {
                continue;
            }
            List<T> list = map.get(e);
            if (list == null) {
                list = new ArrayList<>();
                map.put(e, list);
            }
            list.add(t);
        }
        Map<E, Table<T>> group = new LinkedHashMap<>(map.size() * 2);
        for (Map.Entry<E, List<T>> entry : map.entrySet()) {
            List<T> value = entry.getValue();
            group.put(entry.getKey(), of(value));
        }
        return group;
    }

    public <E> Map<E, List<T>> groupByAsList(boolean removeNullKey, Function<T, E> function) {
        Map<E, List<T>> map = new LinkedHashMap<>(estimateSize() * 2);
        for (T t : this) {
            E e = function.apply(t);
            if (e == null && removeNullKey) {
                continue;
            }
            List<T> list = map.get(e);
            if (list == null) {
                list = new ArrayList<>();
                map.put(e, list);
            }
            list.add(t);
        }
        return map;
    }

    /**
     * 将元素转换成map
     * @param removeNullKey 移除null的key值
     * @param removeNullValue 移除null的value值
     * @param keyFunction
     * @param valueFunction
     * @param <K>
     * @param <V>
     * @return
     */
    public <K, V> Map<K, V> map(
            boolean removeNullKey, boolean removeNullValue,
            Function<T, K> keyFunction, Function<T, V> valueFunction) {
        Map<K, V> map = new LinkedHashMap<>(estimateSize() * 2);
        for (T t : this) {
            K key = keyFunction.apply(t);
            V value = valueFunction.apply(t);
            if (removeNullKey && key == null) {
                continue;
            }
            if (removeNullValue && value == null) {
                continue;
            }
            map.put(key, value);
        }
        return map;
    }

    public <K, V> Map<K, V> map(Function<T, K> keyFunction, Function<T, V> valueFunction) {
        return map(true, true, keyFunction, valueFunction);
    }

    public <K> Map<K, T> mapKey(Function<T, K> keyFunction) {
        return map(true, true, keyFunction, Function.identity());
    }

    public <V> Map<T, V> mapValue(Function<T, V> valueFunction) {
        return map(true, true, Function.identity(), valueFunction);
    }

    public Table<T> orderBy(Comparator<T> comparator) {
        if (comparator == null) {
            throw new IllegalArgumentException();
        }
        List<T> list = list();
        list.sort(comparator);
        return of(list);
    }

    public Table<T> orderByDesc(Comparator<T> comparator) {
        if (comparator == null) {
            throw new IllegalArgumentException();
        }
        List<T> list = list();
        list.sort(comparator.reversed());
        return of(list);
    }

    /**
     * 根据Long值排序
     * @param nullAs 对于null值当做何值处理, 如果传入null, 则过滤null值的元素
     * @param function
     * @return
     */
    public Table<T> orderByLong(Long nullAs, Function<T, Long> function) {
        List<LongNode<T>> list = mapList(t -> {
            Long value = function.apply(t);
            if (value == null && nullAs == null) {
                return null;
            }
            if (value == null) {
                value = nullAs;
            }
            return new LongNode<>(t, value);
        });
        list.sort(Comparator.comparingLong(t -> t.value));
        return Table.of(list).map(t -> t.data);
    }

    /**
     * 根据Long值排序
     * @param nullAs 对于null值当做何值处理, 如果传入null, 则过滤null值的元素
     * @param function
     * @return
     */
    public Table<T> orderByLongDesc(Long nullAs, Function<T, Long> function) {
        List<LongNode<T>> list = mapList(t -> {
            Long value = function.apply(t);
            if (value == null && nullAs == null) {
                return null;
            }
            if (value == null) {
                value = nullAs;
            }
            return new LongNode<>(t, value);
        });
        list.sort((c1, c2) -> - Long.compare(c1.value, c2.value));
        return Table.of(list).map(t -> t.data);
    }

    public Table<T> orderByDate(Long nullAs, Function<T, Date> function) {
        return orderByLong(nullAs, t -> {
            Date date = function.apply(t);
            return date == null ? null : date.getTime();
        });
    }

    public Table<T> orderByDateDesc(Long nullAs, Function<T, Date> function) {
        return orderByLongDesc(nullAs, t -> {
            Date date = function.apply(t);
            return date == null ? null : date.getTime();
        });
    }

    /**
     * 根据Int值排序
     * @param nullAs 对于null值当做何值处理, 如果传入null, 则过滤null值的元素
     * @param function
     * @return
     */
    public Table<T> orderByInt(Integer nullAs, Function<T, Integer> function) {
        List<IntNode<T>> list = mapList(t -> {
            Integer value = function.apply(t);
            if (value == null && nullAs == null) {
                return null;
            }
            if (value == null) {
                value = nullAs;
            }
            return new IntNode<>(t, value);
        });
        list.sort(Comparator.comparingInt(c -> c.value));
        return Table.of(list).map(t -> t.data);
    }

    /**
     * 根据Int值排序
     * @param nullAs 对于null值当做何值处理, 如果传入null, 则过滤null值的元素
     * @param function
     * @return
     */
    public Table<T> orderByIntDesc(Integer nullAs, Function<T, Integer> function) {
        List<IntNode<T>> list = mapList(t -> {
            Integer value = function.apply(t);
            if (value == null && nullAs == null) {
                return null;
            }
            if (value == null) {
                value = nullAs;
            }
            return new IntNode<>(t, value);
        });
        list.sort((c1, c2) -> - Integer.compare(c1.value, c2.value));
        return Table.of(list).map(t -> t.data);
    }

    /**
     * 根据Double值排序
     * @param nullAs 对于null值当做何值处理, 如果传入null, 则过滤null值的元素
     * @param function
     * @return
     */
    public Table<T> orderByDouble(Double nullAs, Function<T, Double> function) {
        List<DoubleNode<T>> list = mapList(t -> {
            Double value = function.apply(t);
            if (value == null && nullAs == null) {
                return null;
            }
            if (value == null) {
                value = nullAs;
            }
            return new DoubleNode<>(t, value);
        });
        list.sort(Comparator.comparingDouble(c -> c.value));
        return Table.of(list).map(t -> t.data);
    }

    /**
     * 根据Double值排序
     * @param nullAs 对于null值当做何值处理, 如果传入null, 则过滤null值的元素
     * @param function
     * @return
     */
    public Table<T> orderByDoubleDesc(Double nullAs, Function<T, Double> function) {
        List<DoubleNode<T>> list = mapList(t -> {
            Double value = function.apply(t);
            if (value == null && nullAs == null) {
                return null;
            }
            if (value == null) {
                value = nullAs;
            }
            return new DoubleNode<>(t, value);
        });
        list.sort((c1, c2) -> - Double.compare(c1.value, c2.value));
        return Table.of(list).map(t -> t.data);
    }

    public Table<T> orderByString(String nullAs, final Locale locale, Function<T, String> function) {
        List<StringNode<T>> list = mapList(t -> {
            String value = function.apply(t);
            if (value == null && nullAs == null) {
                return null;
            }
            if (value == null) {
                value = nullAs;
            }
            return new StringNode<>(t, value);
        });
        list.sort((c1, c2) -> {
            final Collator instance = Collator.getInstance(
                    locale == null ? Locale.getDefault() : locale);
            return instance.compare(c1.value, c2.value);
        });
        return Table.of(list).map(t -> t.data);
    }

    public Table<T> orderByStringDesc(String nullAs, final Locale locale, Function<T, String> function) {
        List<StringNode<T>> list = mapList(t -> {
            String value = function.apply(t);
            if (value == null && nullAs == null) {
                return null;
            }
            if (value == null) {
                value = nullAs;
            }
            return new StringNode<>(t, value);
        });
        list.sort((c1, c2) -> {
            final Collator instance = Collator.getInstance(
                    locale == null ? Locale.getDefault() : locale);
            return - instance.compare(c1.value, c2.value);
        });
        return Table.of(list).map(t -> t.data);
    }

    /**
     * 只返回非null元素的迭代器(过滤掉所有null元素)
     *
     * @param <T>
     */
    private static final class NonNullIterator<T> implements Iterator<T> {

        private final Iterator<T> iterator;
        private T nextNonNullElement;

        public NonNullIterator(Iterator<T> iterator) {
            this.iterator = iterator;
            this.nextNonNullElement = null;
        }

        @Override
        public boolean hasNext() {
            if (this.nextNonNullElement != null) {
                return true;
            }
            while (iterator.hasNext()) {
                T next = iterator.next();
                if (next != null) {
                    this.nextNonNullElement = next;
                    return true;
                }
            }
            return false;
        }

        @Override
        public T next() {
            if (this.nextNonNullElement != null) {
                T next = this.nextNonNullElement;
                this.nextNonNullElement = null;
                return next;
            }
            throw new IllegalStateException("call after hasNext return true");
        }

        @Override
        public void remove() {
            throw new UnsupportedOperationException("table is immutable");
        }
    }

    private static final class LongNode<T> {
        final T data;
        final long value;

        public LongNode(T data, long value) {
            this.data = data;
            this.value = value;
        }
    }

    private static final class IntNode<T> {
        final T data;
        final int value;

        public IntNode(T data, int value) {
            this.data = data;
            this.value = value;
        }
    }

    private static final class DoubleNode<T> {
        final T data;
        final double value;

        public DoubleNode(T data, double value) {
            this.data = data;
            this.value = value;
        }
    }

    private static final class StringNode<T> {
        final T data;
        final String value;

        public StringNode(T data, String value) {
            this.data = data;
            this.value = value;
        }
    }

    private static <T> void ofClass(List<T> data, Class<T> clazz, Object element, Set<Object> visitedElements) {
        if (element == null || visitedElements.contains(element)) {
            return;
        }
        visitedElements.add(element);
        if (clazz.isAssignableFrom(element.getClass())) {
            data.add((T) element);
        }
        if (element instanceof Iterable) {
            for (Object e : ((Iterable) element)) {
                ofClass(data, clazz, e, visitedElements);
            }
        }
    }

    private boolean appendTextWithTrim(StringBuilder sb, CharSequence cs, String delimiters, boolean firstAppendHasSuccess) {
        if (cs == null) {
            return false;
        }
        int length = cs.length();
        if (length == 0) {
            return false;
        }
        int first = -1;
        for (int i = 0; i < length; i++) {
            if (!Character.isWhitespace(cs.charAt(i))) {
                first = i;
                break;
            }
        }
        // 全是whitespace
        if (first == -1) {
            return false;
        }
        int last = length - 1;
        for (int i = last; i >= 0; i--) {
            if (!Character.isWhitespace(cs.charAt(i))) {
                last = i;
                break;
            }
        }
        if (firstAppendHasSuccess && delimiters != null) {
            sb.append(delimiters);
        }
        sb.append(cs, first, last + 1);
        return true;
    }

    private int estimateSize() {
        if (data instanceof Collection) {
            return ((Collection<T>) data).size();
        }
        return 16;
    }

    private Table(Iterable<T> data) {
        if (data == null) {
            this.data = (Table<T>) EMPTY_TABLE;
        } else {
            this.data = data;
        }
    }
}
