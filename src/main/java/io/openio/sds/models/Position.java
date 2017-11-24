package io.openio.sds.models;

import static io.openio.sds.common.Check.checkArgument;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 
 *
 *
 */
public class Position {

    private static final Pattern POSITION_PATTERN = Pattern
            .compile("^([\\d]+)(\\.([\\d]+))?$");

    private int meta;
    private int sub;

    private Position(int meta, int sub) {
        this.meta = meta;
        this.sub = sub;
    }

    public static Position parse(String pos) {
        Matcher m = POSITION_PATTERN.matcher(pos);
        checkArgument(m.matches(),
                String.format("Invalid position %s", pos));
        if (null == m.group(2))
            return simple(Integer.parseInt(m.group(1)));
        return composed(Integer.parseInt(m.group(1)),
                Integer.parseInt(m.group(3)));
    }

    public static Position simple(int meta) {
        checkArgument(0 <= meta, "Invalid position");
        return new Position(meta, -1);
    }

    public static Position composed(int meta, int sub) {
        checkArgument(0 <= meta, "Invalid meta position");
        checkArgument(0 <= sub, "Invalid sub position");
        return new Position(meta, sub);
    }

    public int meta() {
        return meta;
    }

    public int sub() {
        return sub;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder().append(meta);
        if (-1 != sub)
            sb.append(".").append(sub);
        return sb.toString();
    }

    /**
     * Returns negative, 0 or positive int if the position is lower, equals or
     * higher than the specified one .
     * <p>
     * This method compares the meta position, if equals,
     * it compares the sub position.
     * 
     * @param pos
     *            the position to compare to
     * @return negative, 0 or positive int if the position is lower, equals or
     * higher than the specified one .
     */
    public int compare(Position pos) {
        if (meta == pos.meta()) return sub - pos.sub();
        else return meta - pos.meta();
    }
}
