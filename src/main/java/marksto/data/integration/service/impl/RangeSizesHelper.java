package marksto.data.integration.service.impl;

import com.google.api.services.sheets.v4.model.ValueRange;
import marksto.common.annotation.AddTestCoverage;

import java.util.function.UnaryOperator;
import java.util.regex.Pattern;

import static marksto.common.util.GeneralUtils.*;

@AddTestCoverage
class RangeSizesHelper {

    private static final Pattern RANGE_PATTERN = Pattern.compile(".+!(?<start>(?<sCol>[A-Z]+)(?<sRow>\\d+))(:(?<end>(?<eCol>[A-Z]+)(?<eRow>\\d+)))?");

    private static UnaryOperator<String> changeNumValueBy(final int delta) {
        return numStr -> String.valueOf(Integer.valueOf(retainDigits(numStr)) + delta);
    }

    private int getRangeRowsNum(String rangeStr) {
        String startRow = getPatternGroup(RANGE_PATTERN, "sRow", rangeStr);
        String endRow = getPatternGroup(RANGE_PATTERN, "eRow", rangeStr);
        if (startRow == null) {
            return 0; // does nothing then
        }
        if (endRow == null) {
            return 1; // single cell range
        }
        return Integer.valueOf(endRow) - Integer.valueOf(startRow) + 1;
    }

    String getIncreasedRangeStr(String rangeStr, int rowsDelta) {
        boolean isSingleCellRange = !rangeStr.contains(":");
        if (isSingleCellRange) {
            rangeStr = rangeStr + ":" + getPatternGroup(RANGE_PATTERN, "start", rangeStr);
        }
        return replacePatternGroup(RANGE_PATTERN, "eRow", rangeStr, changeNumValueBy(rowsDelta));
    }

    int getRowsDelta(ValueRange aValueRange, ValueRange bValueRange) {
        return getRangeRowsNum(aValueRange.getRange()) - getRangeRowsNum(bValueRange.getRange());
    }

    int getBiggerRangeDimension(ValueRange aValueRange, ValueRange bValueRange) {
        return Math.max(
                getRangeRowsNum(aValueRange.getRange()),
                getRangeRowsNum(bValueRange.getRange())
        );
    }

}
