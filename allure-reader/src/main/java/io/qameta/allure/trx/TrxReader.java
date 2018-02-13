package io.qameta.allure.trx;

import io.qameta.allure.ResultsReader;
import io.qameta.allure.ResultsVisitor;
import io.qameta.allure.entity.TestResult;
import io.qameta.allure.entity.TestStatus;
import io.qameta.allure.parser.XmlElement;
import io.qameta.allure.util.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.xml.sax.SAXException;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.chrono.ChronoZonedDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import static io.qameta.allure.entity.LabelName.RESULT_FORMAT;
import static java.util.Objects.nonNull;

/**
 * @author charlie (Dmitry Baev).
 */
public class TrxReader implements ResultsReader {

    private static final Logger LOGGER = LoggerFactory.getLogger(TrxReader.class);

    private static final String TEST_RUN_ELEMENT_NAME = "TestRun";

    public static final String TRX_RESULTS_FORMAT = "trx";
    public static final String RESULTS_ELEMENT_NAME = "Results";
    public static final String UNIT_TEST_RESULT_ELEMENT_NAME = "UnitTestResult";
    public static final String TEST_NAME_ATTRIBUTE = "testName";
    public static final String START_TIME_ATTRIBUTE = "startTime";
    public static final String END_TIME_ATTRIBUTE = "endTime";
    public static final String OUTCOME_ATTRIBUTE = "outcome";
    public static final String TEST_DEFINITIONS_ELEMENT = "TestDefinitions";
    public static final String UNIT_TEST_ELEMENT = "UnitTest";
    public static final String NAME_ATTRIBUTE = "name";
    public static final String PROPERTIES_ELEMENT = "Properties";
    public static final String PROPERTY_ATTRIBUTE = "Property";
    public static final String KEY_ELEMENT = "Key";
    public static final String VALUE_ELEMENT = "Value";
    public static final String DESCRIPTION_ELEMENT = "Description";
    public static final String EXECUTION_ELEMENT = "Execution";
    public static final String ID_ATTRIBUTE = "id";
    public static final String EXECUTION_ID_ATTRIBUTE = "executionId";
    public static final String OUTPUT_ELEMENT_NAME = "Output";
    public static final String MESSAGE_ELEMENT_NAME = "Message";
    public static final String STACK_TRACE_ELEMENT_NAME = "StackTrace";
    public static final String ERROR_INFO_ELEMENT_NAME = "ErrorInfo";

    @SuppressWarnings("all")
    @Override
    public void readResultFile(final ResultsVisitor visitor, final Path file) {
        if (FileUtils.endsWith(file, ".trx")) {
            parseTestRun(visitor, file);
        }
    }

    protected void parseTestRun(final ResultsVisitor visitor, final Path parsedFile) {
        try {
            LOGGER.debug("Parsing file {}", parsedFile);

            final DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            final DocumentBuilder builder = factory.newDocumentBuilder();
            final Document document = builder.parse(parsedFile.toFile());
            final XmlElement testRunElement = new XmlElement(document.getDocumentElement());
            final String elementName = testRunElement.getName();
            if (!TEST_RUN_ELEMENT_NAME.equals(elementName)) {
                LOGGER.debug("{} is not a valid TRX file. Unknown root element {}", parsedFile, elementName);
                return;
            }
            final Map<String, TrxUnitTest> tests = new HashMap<>();
            testRunElement.getFirst(TEST_DEFINITIONS_ELEMENT)
                    .ifPresent(testDefinitions -> {
                        testDefinitions.get(UNIT_TEST_ELEMENT).forEach(unitTestElement -> {
                            final TrxUnitTest unitTest = parseUnitTest(unitTestElement);
                            tests.put(unitTest.getExecutionId(), unitTest);
                        });
                    });
            testRunElement.getFirst(RESULTS_ELEMENT_NAME)
                    .ifPresent(resultsElement -> parseResults(resultsElement, tests, visitor));
        } catch (SAXException | ParserConfigurationException | IOException e) {
            LOGGER.error("Could not parse file {}: {}", parsedFile, e);
        }
    }

    protected TrxUnitTest parseUnitTest(final XmlElement unitTestElement) {
        final String name = unitTestElement.getAttribute(NAME_ATTRIBUTE);
        final String description = unitTestElement.getFirst(DESCRIPTION_ELEMENT)
                .map(XmlElement::getValue)
                .orElse(null);
        final String executionId = unitTestElement.getFirst(EXECUTION_ELEMENT)
                .map(execution -> execution.getAttribute(ID_ATTRIBUTE))
                .orElse(null);
        final Map<String, String> properties = parseProperties(unitTestElement);
        return new TrxUnitTest(name, executionId, description, properties);
    }

    private Map<String, String> parseProperties(final XmlElement unitTestElement) {
        final Map<String, String> properties = new HashMap<>();
        unitTestElement.getFirst(PROPERTIES_ELEMENT)
                .ifPresent(propertiesElement -> parseProperties(properties, propertiesElement));
        return properties;
    }

    private void parseProperties(final Map<String, String> properties, final XmlElement propertiesElement) {
        propertiesElement.get(PROPERTY_ATTRIBUTE)
                .forEach(propertyElement -> parseProperty(properties, propertyElement));
    }

    private void parseProperty(final Map<String, String> properties, final XmlElement propertyElement) {
        final Optional<String> key = propertyElement.getFirst(KEY_ELEMENT)
                .map(XmlElement::getValue);
        final Optional<String> value = propertyElement.getFirst(VALUE_ELEMENT)
                .map(XmlElement::getValue);
        if (key.isPresent() && value.isPresent()) {
            properties.put(key.get(), value.get());
        }
    }

    protected void parseResults(final XmlElement resultsElement,
                                final Map<String, TrxUnitTest> tests,
                                final ResultsVisitor visitor) {
        resultsElement.get(UNIT_TEST_RESULT_ELEMENT_NAME)
                .forEach(unitTestResult -> parseUnitTestResult(unitTestResult, tests, visitor));
    }

    protected void parseUnitTestResult(final XmlElement unitTestResult,
                                       final Map<String, TrxUnitTest> tests,
                                       final ResultsVisitor visitor) {
        final String executionId = unitTestResult.getAttribute(EXECUTION_ID_ATTRIBUTE);
        final String testName = unitTestResult.getAttribute(TEST_NAME_ATTRIBUTE);
        final String startTime = unitTestResult.getAttribute(START_TIME_ATTRIBUTE);
        final String endTime = unitTestResult.getAttribute(END_TIME_ATTRIBUTE);
        final String outcome = unitTestResult.getAttribute(OUTCOME_ATTRIBUTE);
        final TestResult result = new TestResult()
                .setName(testName)
                .setStatus(parseStatus(outcome));
        parseTime(startTime).ifPresent(result::setStart);
        parseTime(endTime).ifPresent(result::setStop);
        if (nonNull(result.getStart()) && nonNull(result.getStop())) {
            result.setDuration(Math.max(result.getStop() - result.getStart(), 0));
        }

        getStatusMessage(unitTestResult).ifPresent(result::setMessage);
        getStatusTrace(unitTestResult).ifPresent(result::setTrace);
        Optional.ofNullable(tests.get(executionId)).ifPresent(unitTest -> {
            result.setParameters(unitTest.getParameters());
            result.setDescription(unitTest.getDescription());
        });

        result.addLabelIfNotExists(RESULT_FORMAT, TRX_RESULTS_FORMAT);
        visitor.visitTestResult(result);
    }

    private Optional<String> getStatusMessage(final XmlElement unitTestResult) {
        return unitTestResult.getFirst(OUTPUT_ELEMENT_NAME)
                .flatMap(output -> output.getFirst(ERROR_INFO_ELEMENT_NAME))
                .flatMap(output -> output.getFirst(MESSAGE_ELEMENT_NAME))
                .map(XmlElement::getValue);
    }

    private Optional<String> getStatusTrace(final XmlElement unitTestResult) {
        return unitTestResult.getFirst(OUTPUT_ELEMENT_NAME)
                .flatMap(output -> output.getFirst(ERROR_INFO_ELEMENT_NAME))
                .flatMap(output -> output.getFirst(STACK_TRACE_ELEMENT_NAME))
                .map(XmlElement::getValue);
    }

    protected TestStatus parseStatus(final String outcome) {
        if (Objects.isNull(outcome)) {
            return TestStatus.UNKNOWN;
        }
        switch (outcome.toLowerCase()) {
            case "passed":
                return TestStatus.PASSED;
            case "failed":
                return TestStatus.FAILED;
            default:
                return TestStatus.UNKNOWN;
        }
    }

    protected Optional<Long> parseTime(final String time) {
        try {
            return Optional.ofNullable(time)
                    .map(ZonedDateTime::parse)
                    .map(ChronoZonedDateTime::toInstant)
                    .map(Instant::getEpochSecond);
        } catch (Exception e) {
            LOGGER.error("Could not parse time {}", time, e);
            return Optional.empty();
        }
    }
}
