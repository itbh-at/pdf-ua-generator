/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 IT Beratung Hermann GmbH
 */

package at.itbh.pdfuagen.core;

import io.quarkus.qute.EvalContext;
import io.quarkus.qute.TemplateException;
import io.quarkus.qute.TemplateInstance;
import io.quarkus.qute.ValueResolver;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.FormatStyle;
import java.util.Currency;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Formatting functions for template data, following the language of the rendered template variant
 * ({@link TemplateInstance#LOCALE}):
 *
 * <ul>
 *   <li>{@code {amount.number}} — grouped, with the decimals of the value; {@code
 *       {amount.number(2)}} with exactly two decimals
 *   <li>{@code {amount.currency('EUR')}} — amount in an ISO 4217 currency
 *   <li>{@code {day.date}} — an ISO 8601 date ({@code 2026-09-19}) in medium style; {@code
 *       {day.date('long')}} with {@code short}, {@code medium}, {@code long} or {@code full}
 * </ul>
 */
public final class Formatters {

  /** Functions available on number fields. */
  public static final Set<String> NUMBER_FUNCTIONS = Set.of("number", "currency");

  /** Functions available on date fields. */
  public static final Set<String> DATE_FUNCTIONS = Set.of("date");

  private Formatters() {}

  static List<ValueResolver> resolvers() {
    return List.of(new NumberResolver(), new DateResolver());
  }

  private static Locale locale(EvalContext ctx) {
    return switch (ctx.getAttribute(TemplateInstance.LOCALE)) {
      case Locale locale -> locale;
      case String tag -> Locale.forLanguageTag(tag);
      case null, default -> Locale.ROOT;
    };
  }

  private static CompletionStage<Object> param(EvalContext ctx, int index) {
    return ctx.getParams().size() > index
        ? ctx.evaluate(ctx.getParams().get(index))
        : CompletableFuture.completedFuture(null);
  }

  private static final class NumberResolver implements ValueResolver {

    @Override
    public boolean appliesTo(EvalContext ctx) {
      return ctx.getBase() instanceof Number && NUMBER_FUNCTIONS.contains(ctx.getName());
    }

    @Override
    public CompletionStage<Object> resolve(EvalContext ctx) {
      BigDecimal value = decimal((Number) ctx.getBase());
      Locale locale = locale(ctx);
      return param(ctx, 0)
          .thenApply(
              arg ->
                  switch (ctx.getName()) {
                    case "currency" -> currency(value, arg, locale);
                    default -> number(value, arg, locale);
                  });
    }

    private static String number(BigDecimal value, Object decimals, Locale locale) {
      NumberFormat format = NumberFormat.getNumberInstance(locale);
      int digits =
          switch (decimals) {
            case null -> Math.max(value.scale(), 0);
            case Number n when n.intValue() >= 0 && n.intValue() <= 20 -> n.intValue();
            default ->
                throw new TemplateException(
                    "number(…) expects a number of decimals from 0 to 20, got " + decimals);
          };
      format.setMinimumFractionDigits(digits);
      format.setMaximumFractionDigits(digits);
      format.setRoundingMode(RoundingMode.HALF_UP);
      return format.format(value);
    }

    private static String currency(BigDecimal value, Object code, Locale locale) {
      Currency currency;
      try {
        currency = Currency.getInstance(String.valueOf(code));
      } catch (IllegalArgumentException | NullPointerException e) {
        throw new TemplateException(
            "currency(…) expects an ISO 4217 currency code such as 'EUR', got " + code);
      }
      NumberFormat format = NumberFormat.getCurrencyInstance(locale);
      format.setCurrency(currency);
      int digits = Math.max(currency.getDefaultFractionDigits(), 0);
      format.setMinimumFractionDigits(digits);
      format.setMaximumFractionDigits(digits);
      format.setRoundingMode(RoundingMode.HALF_UP);
      return format.format(value);
    }

    private static BigDecimal decimal(Number number) {
      return switch (number) {
        case BigDecimal d -> d;
        case BigInteger i -> new BigDecimal(i);
        case Integer i -> BigDecimal.valueOf(i);
        case Long l -> BigDecimal.valueOf(l);
        default -> new BigDecimal(number.toString());
      };
    }
  }

  private static final class DateResolver implements ValueResolver {

    @Override
    public boolean appliesTo(EvalContext ctx) {
      return ctx.getBase() instanceof String && DATE_FUNCTIONS.contains(ctx.getName());
    }

    @Override
    public CompletionStage<Object> resolve(EvalContext ctx) {
      String text = (String) ctx.getBase();
      Locale locale = locale(ctx);
      return param(ctx, 0).thenApply(style -> date(text, style, locale));
    }

    private static String date(String text, Object style, Locale locale) {
      LocalDate date;
      try {
        date = LocalDate.parse(text);
      } catch (DateTimeParseException e) {
        throw new TemplateException(
            "date expects an ISO 8601 date such as 2026-09-19, got " + text);
      }
      FormatStyle formatStyle =
          switch (style == null ? "medium" : String.valueOf(style)) {
            case "short" -> FormatStyle.SHORT;
            case "medium" -> FormatStyle.MEDIUM;
            case "long" -> FormatStyle.LONG;
            case "full" -> FormatStyle.FULL;
            default ->
                throw new TemplateException(
                    "date(…) expects 'short', 'medium', 'long' or 'full', got " + style);
          };
      return DateTimeFormatter.ofLocalizedDate(formatStyle).withLocale(locale).format(date);
    }
  }
}
