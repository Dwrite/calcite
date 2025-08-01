/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.calcite.sql.parser;

import org.apache.calcite.avatica.util.Casing;
import org.apache.calcite.avatica.util.DateTimeUtils;
import org.apache.calcite.config.CalciteSystemProperty;
import org.apache.calcite.rel.type.RelDataTypeSystem;
import org.apache.calcite.runtime.CalciteContextException;
import org.apache.calcite.sql.SqlBinaryOperator;
import org.apache.calcite.sql.SqlCall;
import org.apache.calcite.sql.SqlDateLiteral;
import org.apache.calcite.sql.SqlIntervalLiteral;
import org.apache.calcite.sql.SqlIntervalQualifier;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.SqlLiteral;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.SqlNodeList;
import org.apache.calcite.sql.SqlNumericLiteral;
import org.apache.calcite.sql.SqlOperator;
import org.apache.calcite.sql.SqlPostfixOperator;
import org.apache.calcite.sql.SqlPrefixOperator;
import org.apache.calcite.sql.SqlSpecialOperator;
import org.apache.calcite.sql.SqlTimeLiteral;
import org.apache.calcite.sql.SqlTimeTzLiteral;
import org.apache.calcite.sql.SqlTimestampLiteral;
import org.apache.calcite.sql.SqlTimestampTzLiteral;
import org.apache.calcite.sql.SqlUtil;
import org.apache.calcite.sql.SqlUuidLiteral;
import org.apache.calcite.sql.fun.SqlStdOperatorTable;
import org.apache.calcite.sql.parser.impl.SqlParserImpl;
import org.apache.calcite.sql.type.SqlTypeName;
import org.apache.calcite.util.DateString;
import org.apache.calcite.util.PrecedenceClimbingParser;
import org.apache.calcite.util.TimeString;
import org.apache.calcite.util.TimeWithTimeZoneString;
import org.apache.calcite.util.TimestampString;
import org.apache.calcite.util.TimestampWithTimeZoneString;
import org.apache.calcite.util.TryThreadLocal;
import org.apache.calcite.util.Util;
import org.apache.calcite.util.trace.CalciteTrace;

import com.google.common.collect.ImmutableList;

import org.checkerframework.checker.nullness.qual.Nullable;
import org.slf4j.Logger;

import java.io.StringReader;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.Charset;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.IllformedLocaleException;
import java.util.List;
import java.util.Locale;
import java.util.StringTokenizer;
import java.util.TimeZone;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.regex.Pattern;

import static com.google.common.base.Preconditions.checkArgument;

import static org.apache.calcite.util.Static.RESOURCE;

import static java.lang.Integer.parseInt;
import static java.util.Objects.requireNonNull;

/**
 * Utility methods relating to parsing SQL.
 */
public final class SqlParserUtil {
  //~ Static fields/initializers ---------------------------------------------

  static final Logger LOGGER = CalciteTrace.getParserTracer();

  private static final Pattern UNDERSCORE = Pattern.compile("_+");

  //~ Constructors -----------------------------------------------------------

  private SqlParserUtil() {
  }

  //~ Methods ----------------------------------------------------------------

  /** Returns the character-set prefix of a SQL string literal; returns null if
   * there is none. */
  public static @Nullable String getCharacterSet(String s) {
    if (s.charAt(0) == '\'') {
      return null;
    }
    if (Character.toUpperCase(s.charAt(0)) == 'N') {
      return CalciteSystemProperty.DEFAULT_NATIONAL_CHARSET.value();
    }
    int i = s.indexOf("'");
    return s.substring(1, i); // skip prefixed '_'
  }

  /**
   * Converts the contents of an sql quoted string literal into the
   * corresponding Java string representation (removing leading and trailing
   * quotes and unescaping internal doubled quotes).
   */
  public static String parseString(String s) {
    int i = s.indexOf("'"); // start of body
    if (i > 0) {
      s = s.substring(i);
    }
    return strip(s, "'", "'", "''", Casing.UNCHANGED);
  }

  /**
   * Converts the contents of a SQL quoted character literal with C-style
   * escapes into the corresponding Java string representation.
   *
   * @throws MalformedUnicodeEscape if input contains invalid unicode escapes
   */
  public static String parseCString(String s) throws MalformedUnicodeEscape {
    final String s2 = parseString(s);
    return replaceEscapedChars(s2);
  }

  /**
   * Converts the contents of a character literal  with escapes like those used
   * in the C programming language to the corresponding Java string
   * representation.
   *
   * <p>If the literal "{@code E'a\tc'}" occurs in the SQL source text, then
   * this method will be invoked with the string "{@code a\tc}" (4 characters)
   * and will return a Java string with the three characters 'a', TAB, 'b'.
   *
   * <p>The format is the same as the Postgres; see
   * <a href="https://www.postgresql.org/docs/14/sql-syntax-lexical.html#SQL-SYNTAX-CONSTANTS">
   * Postgres 4.1.2.2. String Constants With C-Style Escapes</a>.
   *
   * @param input String that contains C-style escapes
   * @return String with escapes converted into Java characters
   * @throws MalformedUnicodeEscape if input contains invalid unicode escapes
   */
  public static String replaceEscapedChars(String input)
      throws MalformedUnicodeEscape {
    // The implementation of this method is based on Crate's method
    // Literals.replaceEscapedChars.
    final int length = input.length();
    if (length <= 1) {
      return input;
    }
    final StringBuilder builder = new StringBuilder(length);
    int endIdx;
    for (int i = 0; i < length; i++) {
      char currentChar = input.charAt(i);
      if (currentChar == '\\' && i + 1 < length) {
        char nextChar = input.charAt(i + 1);
        switch (nextChar) {
        case 'b':
          builder.append('\b');
          i++;
          break;
        case 'f':
          builder.append('\f');
          i++;
          break;
        case 'n':
          builder.append('\n');
          i++;
          break;
        case 'r':
          builder.append('\r');
          i++;
          break;
        case 't':
          builder.append('\t');
          i++;
          break;
        case '\\':
        case '\'':
          builder.append(nextChar);
          i++;
          break;
        case 'u':
        case 'U':
          // handle unicode case
          final int charsToConsume = (nextChar == 'u') ? 4 : 8;
          if (i + 1 + charsToConsume >= length) {
            throw new MalformedUnicodeEscape(i);
          }
          endIdx =
              calculateMaxCharsInSequence(input, i + 2, charsToConsume,
                  SqlParserUtil::isHexDigit);
          if (endIdx != i + 2 + charsToConsume) {
            throw new MalformedUnicodeEscape(i);
          }
          builder.appendCodePoint(parseInt(input.substring(i + 2, endIdx), 16));
          i = endIdx - 1; // skip already consumed chars
          break;
        case 'x':
          // handle hex byte case - up to 2 chars for hex value
          endIdx =
              calculateMaxCharsInSequence(input, i + 2, 2,
                  SqlParserUtil::isHexDigit);
          if (endIdx > i + 2) {
            builder.appendCodePoint(parseInt(input.substring(i + 2, endIdx), 16));
            i = endIdx - 1; // skip already consumed chars
          } else {
            // hex sequence unmatched - output original char
            builder.append(nextChar);
            i++;
          }
          break;
        case '0':
        case '1':
        case '2':
        case '3':
          // handle octal case - up to 3 chars
          endIdx =
              calculateMaxCharsInSequence(input, i + 2,
                  2, // first char is already "consumed"
                  SqlParserUtil::isOctalDigit);
          builder.appendCodePoint(parseInt(input.substring(i + 1, endIdx), 8));
          i = endIdx - 1; // skip already consumed chars
          break;
        default:
          // non-valid escaped char sequence
          builder.append(currentChar);
        }
      } else {
        builder.append(currentChar);
      }
    }
    return builder.toString();
  }

  /**
   * Calculates the maximum number of consecutive characters of the
   * {@link CharSequence} argument, starting from {@code beginIndex}, that match
   * a given {@link Predicate}. The number of characters to match are either
   * capped from the {@code maxCharsToMatch} parameter or the sequence length.
   *
   * <p>Examples:
   * <pre>
   * {@code
   *    calculateMaxCharsInSequence("12345", 0, 2, Character::isDigit) -> 2
   *    calculateMaxCharsInSequence("12345", 3, 2, Character::isDigit) -> 5
   *    calculateMaxCharsInSequence("12345", 4, 2, Character::isDigit) -> 5
   * }
   * </pre>
   *
   * @return the index of the first non-matching character
   */
  private static int calculateMaxCharsInSequence(CharSequence seq,
      int beginIndex,
      int maxCharsToMatch,
      Predicate<Character> predicate) {
    int idx = beginIndex;
    final int end = Math.min(seq.length(), beginIndex + maxCharsToMatch);
    while (idx < end && predicate.test(seq.charAt(idx))) {
      idx++;
    }
    return idx;
  }

  public static BigDecimal parseDecimal(String s) {
    return new BigDecimal(s);
  }

  public static BigDecimal parseInteger(String s) {
    return new BigDecimal(s);
  }

  /**
   * Returns true if the specific character is a base-8 digit.
   */
  public static boolean isOctalDigit(final char ch) {
    return ch >= '0' && ch <= '7';
  }

  /**
   * Returns true if the specified character is a base-16 digit.
   */
  public static boolean isHexDigit(final char ch) {
    return (ch >= '0' && ch <= '9')
        || (ch >= 'A' && ch <= 'F')
        || (ch >= 'a' && ch <= 'f');
  }

  // CHECKSTYLE: IGNORE 1
  /** @deprecated this method is not localized for Farrago standards */
  @Deprecated // to be removed before 2.0
  public static java.sql.Date parseDate(String s) {
    return java.sql.Date.valueOf(s);
  }

  // CHECKSTYLE: IGNORE 1
  /** @deprecated Does not parse SQL:99 milliseconds */
  @Deprecated // to be removed before 2.0
  public static java.sql.Time parseTime(String s) {
    return java.sql.Time.valueOf(s);
  }

  // CHECKSTYLE: IGNORE 1
  /** @deprecated this method is not localized for Farrago standards */
  @Deprecated // to be removed before 2.0
  public static java.sql.Timestamp parseTimestamp(String s) {
    return java.sql.Timestamp.valueOf(s);
  }

  public static SqlDateLiteral parseDateLiteral(String s, SqlParserPos pos) {
    final Calendar cal =
        DateTimeUtils.parseDateFormat(s, Format.get().date,
            DateTimeUtils.UTC_ZONE);
    if (cal == null) {
      throw SqlUtil.newContextException(pos,
          RESOURCE.illegalLiteral("DATE", s,
              RESOURCE.badFormat(DateTimeUtils.DATE_FORMAT_STRING).str()));
    }
    final DateString d = DateString.fromCalendarFields(cal);
    return SqlLiteral.createDate(d, pos);
  }

  public static SqlNumericLiteral parseDecimalLiteral(String s, SqlParserPos pos) {
    try {
      // The s maybe scientific notation string,e.g. 1.2E-3,
      // we need to convert it to 0.0012
      s = new BigDecimal(s).toPlainString();
    } catch (NumberFormatException e) {
      throw SqlUtil.newContextException(pos,
          RESOURCE.invalidLiteral(s, "DECIMAL"));
    }
    return SqlLiteral.createExactNumeric(s, pos);
  }

  public static SqlTimeLiteral parseTimeLiteral(String s, SqlParserPos pos) {
    final DateTimeUtils.PrecisionTime pt =
        DateTimeUtils.parsePrecisionDateTimeLiteral(s,
            Format.get().time, DateTimeUtils.UTC_ZONE, -1);
    if (pt == null) {
      throw SqlUtil.newContextException(pos,
          RESOURCE.illegalLiteral("TIME", s,
              RESOURCE.badFormat(DateTimeUtils.TIME_FORMAT_STRING).str()));
    }
    final TimeString t = TimeString.fromCalendarFields(pt.getCalendar())
        .withFraction(pt.getFraction());
    return SqlLiteral.createTime(t, pt.getPrecision(), pos);
  }

  public static SqlTimeTzLiteral parseTimeTzLiteral(
      String s, SqlParserPos pos) {
    // We expect the string to end in a timezone.
    final int lastSpace = s.lastIndexOf(" ");
    DateTimeUtils.PrecisionTime pt = null;
    if (lastSpace >= 0) {
      final String timeZone = s.substring(lastSpace + 1);
      final String time = s.substring(0, lastSpace);

      final TimeZone tz = TimeZone.getTimeZone(timeZone);
      if (tz != null) {
        pt =
            DateTimeUtils.parsePrecisionDateTimeLiteral(time, Format.get().time, tz, -1);
      }
    }
    if (pt == null) {
      throw SqlUtil.newContextException(pos,
          RESOURCE.illegalLiteral("TIME WITH TIME ZONE", s,
              RESOURCE.badFormat(DateTimeUtils.TIME_FORMAT_STRING).str()));
    }
    final TimeWithTimeZoneString t = TimeWithTimeZoneString.fromCalendarFields(pt.getCalendar())
        .withFraction(pt.getFraction());
    return SqlLiteral.createTime(t, pt.getPrecision(), pos);
  }

  public static SqlTimestampLiteral parseTimestampLiteral(String s,
      SqlParserPos pos) {
    return parseTimestampLiteral(SqlTypeName.TIMESTAMP, s, pos);
  }

  public static SqlTimestampLiteral parseTimestampWithLocalTimeZoneLiteral(
      String s, SqlParserPos pos) {
    return parseTimestampLiteral(SqlTypeName.TIMESTAMP_WITH_LOCAL_TIME_ZONE, s,
        pos);
  }

  public static SqlUuidLiteral parseUuidLiteral(String s, SqlParserPos pos) {
    UUID uuid = UUID.fromString(s);
    return SqlLiteral.createUuid(uuid, pos);
  }

  public static SqlTimestampTzLiteral parseTimestampTzLiteral(
      String s, SqlParserPos pos) {
    // We expect the string to end in a timezone.
    int lastSpace = s.lastIndexOf(" ");
    if (lastSpace >= 0) {
      final String timeZone = s.substring(lastSpace + 1);
      final String timestamp = s.substring(0, lastSpace);
      TimeZone tz = TimeZone.getTimeZone(timeZone);
      if (tz != null) {
        SqlTimestampLiteral ts = parseTimestampLiteral(SqlTypeName.TIMESTAMP, timestamp, pos);
        TimestampWithTimeZoneString tsz = new TimestampWithTimeZoneString(ts.getTimestamp(), tz);
        return SqlLiteral.createTimestamp(tsz, ts.getPrec(), pos);
      }
    }
    throw SqlUtil.newContextException(pos,
        RESOURCE.illegalLiteral("TIMESTAMP WITH TIME ZONE", s,
            RESOURCE.badFormat(DateTimeUtils.TIMESTAMP_FORMAT_STRING).str()));
  }

  private static SqlTimestampLiteral parseTimestampLiteral(SqlTypeName typeName,
      String s, SqlParserPos pos) {
    final Format format = Format.get();
    DateTimeUtils.PrecisionTime pt = null;
    // Allow timestamp literals with and without time fields (as does
    // PostgreSQL); TODO: require time fields except in Babel's lenient mode
    final DateFormat[] dateFormats = {format.timestamp, format.date};
    for (DateFormat dateFormat : dateFormats) {
      pt =
          DateTimeUtils.parsePrecisionDateTimeLiteral(s,
              dateFormat, DateTimeUtils.UTC_ZONE, -1);
      if (pt != null) {
        break;
      }
    }
    if (pt == null) {
      throw SqlUtil.newContextException(pos,
          RESOURCE.illegalLiteral(typeName.getName().replace('_', ' '), s,
              RESOURCE.badFormat(DateTimeUtils.TIMESTAMP_FORMAT_STRING).str()));
    }
    final TimestampString ts =
        TimestampString.fromCalendarFields(pt.getCalendar())
            .withFraction(pt.getFraction());
    return SqlLiteral.createTimestamp(typeName, ts, pt.getPrecision(), pos);
  }

  public static SqlIntervalLiteral parseIntervalLiteral(SqlParserPos pos,
      int sign, String s, SqlIntervalQualifier intervalQualifier) {
    if (s.equals("")) {
      throw SqlUtil.newContextException(pos,
          RESOURCE.illegalIntervalLiteral(s + " "
              + intervalQualifier.toString(), pos.toString()));
    }
    return SqlLiteral.createInterval(sign, s, intervalQualifier, pos);
  }

  /**
   * Parses string to array literal
   * using {@link org.apache.calcite.sql.parser.impl.SqlParserImpl} parser.
   * String format description can be found at the
   * <a href="https://www.postgresql.org/docs/current/arrays.html#ARRAYS-INPUT">link</a>
   *
   * @param s a string to parse
   * @return a array value
   *
   * @throws SqlParseException if there is a parse error
   */
  public static SqlNode parseArrayLiteral(String s) throws SqlParseException {
    SqlAbstractParserImpl parser =
        SqlParserImpl.FACTORY.getParser(new StringReader(s));
    return parser.parseArray();
  }

  /**
   * Checks if the date/time format is valid, throws if not.
   *
   * @param pattern {@link SimpleDateFormat}  pattern
   */
  public static void checkDateFormat(String pattern) {
    SimpleDateFormat df = new SimpleDateFormat(pattern, Locale.ROOT);
    Util.discard(df);
  }

  /**
   * Converts the interval value into a millisecond representation.
   *
   * @param interval Interval
   * @return a long value that represents millisecond equivalent of the
   * interval value.
   */
  public static long intervalToMillis(
      SqlIntervalLiteral.IntervalValue interval) {
    return intervalToMillis(
        interval.getIntervalLiteral(),
        interval.getIntervalQualifier());
  }

  public static long intervalToMillis(
      String literal,
      SqlIntervalQualifier intervalQualifier) {
    checkArgument(!intervalQualifier.isYearMonth(),
        "interval must be day time");
    int[] ret;
    try {
      ret =
          intervalQualifier.evaluateIntervalLiteral(literal,
              intervalQualifier.getParserPosition(), RelDataTypeSystem.DEFAULT);
    } catch (CalciteContextException e) {
      throw new RuntimeException("while parsing day-to-second interval "
          + literal, e);
    }
    long l = 0;
    long[] conv = new long[5];
    conv[4] = 1; // millisecond
    conv[3] = conv[4] * 1000; // second
    conv[2] = conv[3] * 60; // minute
    conv[1] = conv[2] * 60; // hour
    conv[0] = conv[1] * 24; // day
    for (int i = 1; i < ret.length; i++) {
      l += conv[i - 1] * ret[i];
    }
    return ret[0] * l;
  }

  /**
   * Converts the interval value into a months representation.
   *
   * @param interval Interval
   * @return a long value that represents months equivalent of the interval
   * value.
   */
  public static long intervalToMonths(
      SqlIntervalLiteral.IntervalValue interval) {
    return intervalToMonths(
        interval.getIntervalLiteral(),
        interval.getIntervalQualifier());
  }

  public static long intervalToMonths(String literal,
      SqlIntervalQualifier intervalQualifier) {
    checkArgument(intervalQualifier.isYearMonth(),
        "interval must be year month");
    int[] ret;
    try {
      ret =
          intervalQualifier.evaluateIntervalLiteral(literal,
              intervalQualifier.getParserPosition(), RelDataTypeSystem.DEFAULT);
    } catch (CalciteContextException e) {
      throw new RuntimeException("Error while parsing year-to-month interval "
          + literal, e);
    }

    long l = 0;
    long[] conv = new long[2];
    conv[1] = 1; // months
    conv[0] = conv[1] * 12; // years
    for (int i = 1; i < ret.length; i++) {
      l += conv[i - 1] * ret[i];
    }
    return ret[0] * l;
  }

  /**
   * Parses a positive int. All characters have to be digits.
   *
   * @see Integer#parseInt(String)
   * @throws java.lang.NumberFormatException if invalid number or leading '-'
   */
  public static int parsePositiveInt(String value) {
    value = value.trim();
    if (value.charAt(0) == '-') {
      throw new NumberFormatException(value);
    }
    return parseInt(value);
  }

  /**
   * Parses a Binary string. SQL:99 defines a binary string as a hexstring
   * with EVEN nbr of hex digits.
   */
  @Deprecated // to be removed before 2.0
  public static byte[] parseBinaryString(String s) {
    s = s.replace(" ", "");
    s = s.replace("\n", "");
    s = s.replace("\t", "");
    s = s.replace("\r", "");
    s = s.replace("\f", "");
    s = s.replace("'", "");

    if (s.length() == 0) {
      return new byte[0];
    }
    assert (s.length() & 1) == 0; // must be even nbr of hex digits

    final int lengthToBe = s.length() / 2;
    s = "ff" + s;
    BigInteger bigInt = new BigInteger(s, 16);
    byte[] ret = new byte[lengthToBe];
    System.arraycopy(
        bigInt.toByteArray(),
        2,
        ret,
        0,
        ret.length);
    return ret;
  }

  /**
   * Converts a quoted identifier, unquoted identifier, or quoted string to a
   * string of its contents.
   *
   * <p>First, if {@code startQuote} is provided, {@code endQuote} and
   * {@code escape} must also be provided, and this method removes quotes.
   *
   * <p>Finally, converts the string to the provided casing.
   */
  public static String strip(String s, @Nullable String startQuote,
      @Nullable String endQuote, @Nullable String escape, Casing casing) {
    if (startQuote != null) {
      return stripQuotes(s, startQuote, requireNonNull(endQuote, "endQuote"),
          requireNonNull(escape, "escape"), casing);
    } else {
      return toCase(s, casing);
    }
  }

  /**
   * Unquotes a quoted string, using different quotes for beginning and end.
   */
  public static String stripQuotes(String s, String startQuote, String endQuote,
      String escape, Casing casing) {
    assert startQuote.length() == 1;
    assert endQuote.length() == 1;
    assert s.startsWith(startQuote) && s.endsWith(endQuote) : s;
    s = s.substring(1, s.length() - 1).replace(escape, endQuote);
    return toCase(s, casing);
  }

  /**
   * Converts an identifier to a particular casing.
   */
  public static String toCase(String s, Casing casing) {
    switch (casing) {
    case TO_UPPER:
      return s.toUpperCase(Locale.ROOT);
    case TO_LOWER:
      return s.toLowerCase(Locale.ROOT);
    default:
      return s;
    }
  }

  /**
   * Trims a string for given characters from left and right. E.g.
   * {@code trim("aBaac123AabC","abBcC")} returns {@code "123A"}.
   */
  public static String trim(
      String s,
      String chars) {
    if (s.length() == 0) {
      return "";
    }

    int start;
    for (start = 0; start < s.length(); start++) {
      char c = s.charAt(start);
      if (chars.indexOf(c) < 0) {
        break;
      }
    }

    int stop;
    for (stop = s.length(); stop > start; stop--) {
      char c = s.charAt(stop - 1);
      if (chars.indexOf(c) < 0) {
        break;
      }
    }

    if (start >= stop) {
      return "";
    }

    return s.substring(start, stop);
  }

  @Deprecated // to be removed before 2.0
  public static StringAndPos findPos(String sql) {
    return StringAndPos.of(sql);
  }

  /**
   * Returns the (1-based) line and column corresponding to a particular
   * (0-based) offset in a string.
   *
   * <p>Converse of {@link #lineColToIndex(String, int, int)}.
   */
  public static int[] indexToLineCol(String sql, int i) {
    int line = 0;
    int j = 0;
    while (true) {
      int prevj = j;
      j = nextLine(sql, j);
      if ((j < 0) || (j > i)) {
        return new int[]{line + 1, i - prevj + 1};
      }
      ++line;
    }
  }

  public static int nextLine(String sql, int j) {
    int rn = sql.indexOf("\r\n", j);
    int r = sql.indexOf("\r", j);
    int n = sql.indexOf("\n", j);
    if ((r < 0) && (n < 0)) {
      assert rn < 0;
      return -1;
    } else if ((rn >= 0) && (rn < n) && (rn <= r)) {
      return rn + 2; // looking at "\r\n"
    } else if ((r >= 0) && (r < n)) {
      return r + 1; // looking at "\r"
    } else {
      return n + 1; // looking at "\n"
    }
  }

  /**
   * Finds the position (0-based) in a string which corresponds to a given
   * line and column (1-based).
   *
   * <p>Converse of {@link #indexToLineCol(String, int)}.
   */
  public static int lineColToIndex(String sql, int line, int column) {
    --line;
    --column;
    int i = 0;
    while (line-- > 0) {
      i = nextLine(sql, i);
    }
    return i + column;
  }

  /** Given a string with carets, double each of them.
   * (This should probably be more sophisticated, and ignore carets within string literals). */
  public static String escapeCarets(String sql) {
    return sql.replace("^", "^^");
  }

  /**
   * Converts a string to a string with one or two carets in it. For example,
   * <code>addCarets("values (foo)", 1, 9, 1, 12)</code> yields "values
   * (^foo^)".  Existing carets are escaped by doubling.
   */
  public static String addCarets(
      String sql,
      int line,
      int col,
      int endLine,
      int endCol) {
    String sqlWithCarets;
    int cut = lineColToIndex(sql, line, col);
    sqlWithCarets = escapeCarets(sql.substring(0, cut)) + "^"
        + escapeCarets(sql.substring(cut));
    if ((col != endCol) || (line != endLine)) {
      cut = lineColToIndex(sqlWithCarets, endLine, endCol);
      if (line == endLine) {
        ++cut; // for caret
      }
      if (cut < sqlWithCarets.length()) {
        sqlWithCarets =
            sqlWithCarets.substring(0, cut)
                + "^" + escapeCarets(sqlWithCarets.substring(cut));
      } else {
        sqlWithCarets += "^";
      }
    }
    return sqlWithCarets;
  }

  public static @Nullable String getTokenVal(String token) {
    // We don't care about the token which are not string
    if (!token.startsWith("\"")) {
      return null;
    }

    // Remove the quote from the token
    int startIndex = token.indexOf("\"");
    int endIndex = token.lastIndexOf("\"");
    String tokenVal = token.substring(startIndex + 1, endIndex);
    char c = tokenVal.charAt(0);
    if (Character.isLetter(c)) {
      return tokenVal;
    }
    return null;
  }

  /**
   * Extracts the values from a collation name.
   *
   * <p>Collation names are on the form <i>charset$locale$strength</i>.
   *
   * @param in The collation name
   * @return A {@link ParsedCollation}
   */
  public static ParsedCollation parseCollation(String in) {
    StringTokenizer st = new StringTokenizer(in, "$");
    String charsetStr = st.nextToken();
    String localeStr = st.nextToken();
    String strength;
    if (st.countTokens() > 0) {
      strength = st.nextToken();
    } else {
      strength =
          CalciteSystemProperty.DEFAULT_COLLATION_STRENGTH.value();
    }

    Charset charset = SqlUtil.getCharset(charsetStr);
    try {
      Locale locale =
          new Locale.Builder().setLanguageTag(
              UNDERSCORE.matcher(localeStr).replaceAll("-")).build();
      return new ParsedCollation(charset, locale, strength);
    } catch (IllformedLocaleException e) {
      throw RESOURCE.illegalLocaleFormat(localeStr).ex();
    }
  }

  @Deprecated // to be removed before 2.0
  public static String[] toStringArray(List<String> list) {
    return list.toArray(new String[0]);
  }

  public static SqlNode[] toNodeArray(List<SqlNode> list) {
    return list.toArray(new SqlNode[0]);
  }

  public static SqlNode[] toNodeArray(SqlNodeList list) {
    return list.toArray(new SqlNode[0]);
  }

  /** Converts "ROW (1, 2)" to "(1, 2)"
   * and "3" to "(3)". */
  public static SqlNodeList stripRow(SqlNode n) {
    final List<SqlNode> list;
    switch (n.getKind()) {
    case ROW:
      list = ((SqlCall) n).getOperandList();
      break;
    default:
      list = ImmutableList.of(n);
    }
    return new SqlNodeList(list, n.getParserPosition());
  }

  @Deprecated // to be removed before 2.0
  public static String rightTrim(
      String s,
      char c) {
    int stop;
    for (stop = s.length(); stop > 0; stop--) {
      if (s.charAt(stop - 1) != c) {
        break;
      }
    }
    if (stop > 0) {
      return s.substring(0, stop);
    }
    return "";
  }

  /**
   * Replaces a range of elements in a list with a single element. For
   * example, if list contains <code>{A, B, C, D, E}</code> then <code>
   * replaceSublist(list, X, 1, 4)</code> returns <code>{A, X, E}</code>.
   */
  public static <T> void replaceSublist(
      List<T> list,
      int start,
      int end,
      T o) {
    requireNonNull(list, "list");
    checkArgument(start < end);
    for (int i = end - 1; i > start; --i) {
      list.remove(i);
    }
    list.set(start, o);
  }

  /**
   * Converts a list of {expression, operator, expression, ...} into a tree,
   * taking operator precedence and associativity into account.
   */
  public static @Nullable SqlNode toTree(List<@Nullable Object> list) {
    if (list.size() == 1
        && list.get(0) instanceof SqlNode) {
      // Short-cut for the simple common case
      return (SqlNode) list.get(0);
    }
    LOGGER.trace("Attempting to reduce {}", list);
    final OldTokenSequenceImpl tokenSequence = new OldTokenSequenceImpl(list);
    final SqlNode node = toTreeEx(tokenSequence, 0, 0, SqlKind.OTHER);
    LOGGER.debug("Reduced {}", node);
    return node;
  }

  /**
   * Converts a list of {expression, operator, expression, ...} into a tree,
   * taking operator precedence and associativity into account.
   *
   * @param list        List of operands and operators. This list is modified as
   *                    expressions are reduced.
   * @param start       Position of first operand in the list. Anything to the
   *                    left of this (besides the immediately preceding operand)
   *                    is ignored. Generally use value 1.
   * @param minPrec     Minimum precedence to consider. If the method encounters
   *                    an operator of lower precedence, it doesn't reduce any
   *                    further.
   * @param stopperKind If not {@link SqlKind#OTHER}, stop reading the list if
   *                    we encounter a token of this kind.
   * @return the root node of the tree which the list condenses into
   */
  public static SqlNode toTreeEx(SqlSpecialOperator.TokenSequence list,
      int start, final int minPrec, final SqlKind stopperKind) {
    PrecedenceClimbingParser parser =
        list.parser(start, token -> {
          if (token instanceof PrecedenceClimbingParser.Op) {
            PrecedenceClimbingParser.Op tokenOp = (PrecedenceClimbingParser.Op) token;
            final SqlOperator op = ((ToTreeListItem) tokenOp.o()).op;
            return stopperKind != SqlKind.OTHER
                && op.kind == stopperKind
                || minPrec > 0
                && op.getLeftPrec() < minPrec;
          } else {
            return false;
          }
        });
    final int beforeSize = parser.all().size();
    parser.partialParse();
    final int afterSize = parser.all().size();
    final SqlNode node = convert(parser.all().get(0));
    list.replaceSublist(start, start + beforeSize - afterSize + 1, node);
    return node;
  }

  private static SqlNode convert(PrecedenceClimbingParser.Token token) {
    switch (token.type) {
    case ATOM:
      return requireNonNull((SqlNode) token.o);
    case CALL:
      final PrecedenceClimbingParser.Call call =
          (PrecedenceClimbingParser.Call) token;
      final List<@Nullable SqlNode> list = new ArrayList<>();
      for (PrecedenceClimbingParser.Token arg : call.args) {
        list.add(convert(arg));
      }
      final ToTreeListItem item = (ToTreeListItem) call.op.o();
      if (list.size() == 1) {
        SqlNode firstItem = list.get(0);
        if (item.op == SqlStdOperatorTable.UNARY_MINUS
            && firstItem instanceof SqlNumericLiteral) {
          return SqlLiteral.createNegative((SqlNumericLiteral) firstItem,
              item.pos.plusAll(list));
        }
        if (item.op == SqlStdOperatorTable.UNARY_PLUS
            && firstItem instanceof SqlNumericLiteral) {
          return firstItem;
        }
      }
      return item.op.createCall(item.pos.plusAll(list), list);
    default:
      throw new AssertionError(token);
    }
  }

  /**
   * Checks a UESCAPE string for validity, and returns the escape character if
   * no exception is thrown.
   *
   * @param s UESCAPE string to check
   * @return validated escape character
   */
  public static char checkUnicodeEscapeChar(String s) {
    if (s.length() != 1) {
      throw RESOURCE.unicodeEscapeCharLength(s).ex();
    }
    char c = s.charAt(0);
    if (Character.isDigit(c)
        || Character.isWhitespace(c)
        || (c == '+')
        || (c == '"')
        || ((c >= 'a') && (c <= 'f'))
        || ((c >= 'A') && (c <= 'F'))) {
      throw RESOURCE.unicodeEscapeCharIllegal(s).ex();
    }
    return c;
  }

  /**
   * Returns whether the reported ParseException tokenImage
   * allows SQL identifier.
   *
   * @param tokenImage The allowed tokens from the ParseException
   * @param expectedTokenSequences Expected token sequences
   *
   * @return true if SQL identifier is allowed
   */
  public static boolean allowsIdentifier(String[] tokenImage, int[][] expectedTokenSequences) {
    // Compares from tailing tokens first because the <IDENTIFIER>
    // was very probably at the tail.
    for (int i = expectedTokenSequences.length - 1; i >= 0; i--) {
      int[] expectedTokenSequence = expectedTokenSequences[i];
      for (int j = expectedTokenSequence.length - 1; j >= 0; j--) {
        if (tokenImage[expectedTokenSequence[j]].equals("<IDENTIFIER>")) {
          return true;
        }
      }
    }

    return false;
  }

  //~ Inner Classes ----------------------------------------------------------

  /** The components of a collation definition, per the SQL standard. */
  public static class ParsedCollation {
    private final Charset charset;
    private final Locale locale;
    private final String strength;

    public ParsedCollation(
        Charset charset,
        Locale locale,
        String strength) {
      this.charset = charset;
      this.locale = locale;
      this.strength = strength;
    }

    public Charset getCharset() {
      return charset;
    }

    public Locale getLocale() {
      return locale;
    }

    public String getStrength() {
      return strength;
    }
  }

  /**
   * Class that holds a {@link SqlOperator} and a {@link SqlParserPos}. Used
   * by {@link SqlSpecialOperator#reduceExpr} and the parser to associate a
   * parsed operator with a parser position.
   */
  public static class ToTreeListItem {
    private final SqlOperator op;
    private final SqlParserPos pos;

    public ToTreeListItem(
        SqlOperator op,
        SqlParserPos pos) {
      this.op = op;
      this.pos = pos;
    }

    @Override public String toString() {
      return op.toString();
    }

    public SqlOperator getOperator() {
      return op;
    }

    public SqlParserPos getPos() {
      return pos;
    }
  }

  /** Implementation of
   * {@link org.apache.calcite.sql.SqlSpecialOperator.TokenSequence}
   * based on an existing parser. */
  private static class TokenSequenceImpl
      implements SqlSpecialOperator.TokenSequence {
    final List<PrecedenceClimbingParser.Token> list;
    final PrecedenceClimbingParser parser;

    private TokenSequenceImpl(PrecedenceClimbingParser parser) {
      this.parser = parser;
      this.list = parser.all();
    }

    @Override public PrecedenceClimbingParser parser(int start,
        Predicate<PrecedenceClimbingParser.Token> predicate) {
      return parser.copy(start, predicate);
    }

    @Override public int size() {
      return list.size();
    }

    @Override public SqlOperator op(int i) {
      ToTreeListItem o =
          (ToTreeListItem) requireNonNull(list.get(i).o,
              () -> "list.get(" + i + ").o is null in " + list);
      return o.getOperator();
    }

    private static SqlParserPos pos(PrecedenceClimbingParser.Token token) {
      switch (token.type) {
      case ATOM:
        return requireNonNull((SqlNode) token.o, "token.o").getParserPosition();
      case CALL:
        final PrecedenceClimbingParser.Call call =
            (PrecedenceClimbingParser.Call) token;
        SqlParserPos pos = ((ToTreeListItem) call.op.o()).pos;
        for (PrecedenceClimbingParser.Token arg : call.args) {
          pos = pos.plus(pos(arg));
        }
        return pos;
      default:
        return requireNonNull((ToTreeListItem) token.o, "token.o").getPos();
      }
    }

    @Override public SqlParserPos pos(int i) {
      return pos(list.get(i));
    }

    @Override public boolean isOp(int i) {
      return list.get(i).o instanceof ToTreeListItem;
    }

    @Override public SqlNode node(int i) {
      return convert(list.get(i));
    }

    @Override public void replaceSublist(int start, int end, SqlNode e) {
      SqlParserUtil.replaceSublist(list, start, end, parser.atom(e));
    }
  }

  /** Implementation of
   * {@link org.apache.calcite.sql.SqlSpecialOperator.TokenSequence}. */
  private static class OldTokenSequenceImpl
      implements SqlSpecialOperator.TokenSequence {
    final List<@Nullable Object> list;

    private OldTokenSequenceImpl(List<@Nullable Object> list) {
      this.list = list;
    }

    @Override public PrecedenceClimbingParser parser(int start,
        Predicate<PrecedenceClimbingParser.Token> predicate) {
      final PrecedenceClimbingParser.Builder builder =
          new PrecedenceClimbingParser.Builder();
      for (Object o : Util.skip(list, start)) {
        if (o instanceof ToTreeListItem) {
          final ToTreeListItem item = (ToTreeListItem) o;
          final SqlOperator op = item.getOperator();
          if (op instanceof SqlPrefixOperator) {
            builder.prefix(item, op.getLeftPrec());
          } else if (op instanceof SqlPostfixOperator) {
            builder.postfix(item, op.getRightPrec());
          } else if (op instanceof SqlBinaryOperator) {
            builder.infix(item, op.getLeftPrec(),
                op.getLeftPrec() < op.getRightPrec());
          } else if (op instanceof SqlSpecialOperator) {
            builder.special(item, op.getLeftPrec(), op.getRightPrec(),
                (parser, op2) -> {
                  final List<PrecedenceClimbingParser.Token> tokens =
                      parser.all();
                  final SqlSpecialOperator op1 =
                      (SqlSpecialOperator) requireNonNull((ToTreeListItem) op2.o, "op2.o").op;
                  SqlSpecialOperator.ReduceResult r =
                      op1.reduceExpr(tokens.indexOf(op2),
                          new TokenSequenceImpl(parser));
                  return new PrecedenceClimbingParser.Result(
                      tokens.get(r.startOrdinal),
                      tokens.get(r.endOrdinal - 1),
                      parser.atom(r.node));
                });
          } else {
            throw new AssertionError();
          }
        } else {
          builder.atom(requireNonNull(o, "o"));
        }
      }
      return builder.build();
    }

    @Override public int size() {
      return list.size();
    }

    @Override public SqlOperator op(int i) {
      ToTreeListItem item =
          (ToTreeListItem) requireNonNull(list.get(i),
              () -> "list.get(" + i + ")");
      return item.op;
    }

    @Override public SqlParserPos pos(int i) {
      final Object o = list.get(i);
      return o instanceof ToTreeListItem
          ? ((ToTreeListItem) o).pos
          : requireNonNull((SqlNode) o, () -> "item " + i + " is null in " + list)
              .getParserPosition();
    }

    @Override public boolean isOp(int i) {
      return list.get(i) instanceof ToTreeListItem;
    }

    @Override public SqlNode node(int i) {
      return requireNonNull((SqlNode) list.get(i));
    }

    @Override public void replaceSublist(int start, int end, SqlNode e) {
      SqlParserUtil.replaceSublist(list, start, end, e);
    }
  }

  /** Pre-initialized {@link DateFormat} objects, to be used within the current
   * thread, because {@code DateFormat} is not thread-safe. */
  private static class Format {
    private static final TryThreadLocal<Format> PER_THREAD =
        TryThreadLocal.withInitial(Format::new);

    private static Format get() {
      return PER_THREAD.get();
    }

    final DateFormat timestamp =
        new SimpleDateFormat(DateTimeUtils.TIMESTAMP_FORMAT_STRING,
            Locale.ROOT);
    final DateFormat time =
        new SimpleDateFormat(DateTimeUtils.TIME_FORMAT_STRING, Locale.ROOT);
    final DateFormat date =
        new SimpleDateFormat(DateTimeUtils.DATE_FORMAT_STRING, Locale.ROOT);
  }

  /** Thrown by {@link #replaceEscapedChars(String)}. */
  public static class MalformedUnicodeEscape extends Exception {
    public final int i;

    MalformedUnicodeEscape(int i) {
      this.i = i;
    }
  }
}
