package com.hotcocoacup.mobiletools.xlstoresouces;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.ConsoleHandler;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.apache.commons.cli.BasicParser;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.poi.hssf.util.CellReference;
import org.apache.poi.openxml4j.exceptions.InvalidFormatException;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellValue;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;

import com.google.gson.Gson;
import com.google.gson.JsonIOException;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;
import com.hotcocoacup.mobiletools.xlstoresouces.AndroidProcessor;
import com.hotcocoacup.mobiletools.xlstoresouces.IosProcessor;
import com.hotcocoacup.mobiletools.xlstoresouces.Processor;
import com.hotcocoacup.mobiletools.xlstoresouces.model.Entry;
import com.hotcocoacup.mobiletools.xlstoresouces.model.KeyValuePair;

public class XlsToResources {

	public static final String VERSION = XlsToResources.class.getPackage().getImplementationVersion();
	public static final String LOGGER_NAME = "XlsToResources";

	private static Logger logger = Logger.getLogger(LOGGER_NAME);
	private static Options options = new Options();

	public static void main(String[] args) {

		// Setting up the logger
		logger.setLevel(Level.INFO);
		logger.setUseParentHandlers(false);

		LogFormatter formatter = new LogFormatter();
		ConsoleHandler handler = new ConsoleHandler();
		handler.setFormatter(formatter);
		logger.addHandler(handler);

		// Parsing the user inputs
		options.addOption("h", "help", false, "Print the help.");
		options.addOption("v", "version", false, "Print the current version.");
		options.addOption("c", "config", true, "The configuration file");
		options.addOption("a", "android", true,
				"The android resouce filename to export");
		options.addOption("i", "ios", true,
				"The iOS resource filename to export");
		options.addOption(new Option("expandgroupby", false, 
				"If a groupBy column exists, and the cell is empty, it will copy the value of the previous cells"));

		CommandLineParser parser = new BasicParser();
		CommandLine cmd = null;

		try {
			cmd = parser.parse(options, args);
		} catch (ParseException e) {
			logger.log(Level.SEVERE, "Failed to parse command line properties",
					e);
			help();
			return;
		}

		// user asked for help...
		if (cmd.hasOption('h')) {
			help();
			return;
		}
		
		// user asked for version
		if (cmd.hasOption('v')) {
			printVersion();
			return;
		}

		// extracting the configuration filename
		String configFileName;
		if (cmd.hasOption('c')) {
			configFileName = cmd.getOptionValue('c');
		} else {
			logger.severe("You must input the configurationFilename");
			help();
			return;
		}
		
		boolean expandGroupby = cmd.hasOption("expandgroupby");

		logger.info("Reading configuration file " + configFileName);
		File file = new File(configFileName);
		Gson gson = new Gson();

		List<Entry> entries;
		try {
			entries = gson.fromJson(new FileReader(file),
					new TypeToken<List<Entry>>() {
					}.getType());
		} catch (JsonIOException e) {
			logger.log(Level.SEVERE, "Cannot parse the configuration file", e);
			return;
		} catch (JsonSyntaxException e) {
			logger.log(Level.SEVERE, "Cannot parse the configuration file", e);
			return;
		} catch (FileNotFoundException e) {
			logger.log(Level.SEVERE, "The configuration file does not exist", e);
			return;
		}

		logger.log(Level.INFO, entries.size()
				+ " entry(ies) found in the configuration file.");

		Map<String, List<KeyValuePair>> map = new HashMap<String, List<KeyValuePair>>();

		int entryCount = 1;
		for (Entry entry : entries) {
			Workbook workbook;

			logger.log(Level.INFO, "Entry #" + entryCount + ": Reading "
					+ entry.getXlsFile() + " ...");

			// parsing the excel file.
			try {
				if (entry.getXlsFile() == null) {
					logger.log(Level.SEVERE, "You must specify an XLS/XLSX file name. Ignoring the entry.");
					continue;
				}
				
				workbook = WorkbookFactory.create(new File(entry.getXlsFile()));
			} catch (InvalidFormatException e) {
				logger.log(Level.SEVERE,
						"Invalid file format. Ignoring this entry.", e);
				continue;
			} catch (IOException e) {
				logger.log(Level.SEVERE,
						"IO error while reading the file. Ignoring the entry.",
						e);
				continue;
			}
			
			FormulaEvaluator evaluator = workbook.getCreationHelper().createFormulaEvaluator();

			// invalid sheet number
			if (entry.getSheet() < 0
					|| entry.getSheet() > workbook.getNumberOfSheets()) {
				logger.log(Level.SEVERE,
						"Sheet index not valid. Ignoring this entry.");
				continue;
			}

			Sheet sheet = workbook.getSheetAt(entry.getSheet());

			int rowEnd;
			if (entry.getRowEnd() == -1) {

				// default rowEnd : read all the rows
				rowEnd = sheet.getLastRowNum();
			} else {

				if (entry.getRowEnd() < 0
						|| entry.getRowEnd() < entry.getRowStart()) {
					logger.log(Level.SEVERE,
							"Invalid row end. Ignoring this entry.");
					continue;
				} else {
					rowEnd = Math.min(sheet.getLastRowNum(),
							entry.getRowEnd() - 1);
				}
			}
			
			String lastGroupbyValue = null;

			// processing all the rows of the file
			for (int i = entry.getRowStart() - 1; i <= rowEnd; i++) {

				Row row = sheet.getRow(i);

				logger.log(Level.FINEST, " processing row: " + i + "...");
				
				if (row == null) {
					logger.log(Level.WARNING, " row: " + i + " is null");
					continue;
				}
				
				Cell keyCell = row.getCell(new CellReference(entry.getColumnKey()).getCol());
				Cell valueCell = row.getCell(new CellReference(entry.getColumnValue()).getCol());

				
				
				String keyStr = getString(evaluator, keyCell);
				String valueStr = getString(evaluator, valueCell);
				
				if (keyStr == null || keyStr.isEmpty()) {
					logger.log(Level.WARNING,
							"Key column " + entry.getColumnKey() + " (row "
									+ (i + 1)
									+ ") does not exist. Skipping row.");
					continue;
				}

				if (valueStr == null || valueStr.isEmpty()) {
					logger.log(Level.WARNING,
							"Value colum " + entry.getColumnValue() + " (row "
									+ (i + 1)
									+ ") does not exist. Skipping row.");
					continue;
				}

				String groupBy = "";
				if (entry.getGroupBy() != null) {
					Cell groupByCell = row.getCell(new CellReference(entry.getGroupBy()).getCol());

					if (groupByCell != null && !groupByCell.getStringCellValue().isEmpty()) {
						groupBy = groupByCell.getStringCellValue();
						lastGroupbyValue = groupBy;
					} else {
						
						if (expandGroupby && lastGroupbyValue != null && !lastGroupbyValue.isEmpty()) {
							groupBy = lastGroupbyValue;
						} else {
						
							logger.log( Level.WARNING,
									"GroupBy column "
											+ entry.getGroupBy()
											+ " (row "
											+ (i + 1)
											+ ") does not exist. GroupBy set to default.");
						}
					}
				}

				KeyValuePair keyValue = new KeyValuePair(keyStr, valueStr);

				add(map, groupBy, keyValue);
			}

			logger.log(Level.INFO, "Entry #" + entryCount
					+ ": Parsed with success.");

			entryCount++;
		}

		if (cmd.hasOption('a')) {
			
			String androidFileName = cmd.getOptionValue('a');
			logger.log(Level.INFO, "Exporting as android resource: " + androidFileName);
			
			Writer outputAndroidStream;
			try {
				outputAndroidStream = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(androidFileName), "UTF8"));
				Processor processorAndroid = new AndroidProcessor();
				processorAndroid.process(outputAndroidStream, map);
				logger.log(Level.INFO, "Exported with success");
			} catch (IOException e) {
				logger.log(Level.SEVERE, "Export failed...", e);
			}

		}
		
		if (cmd.hasOption('i')) {
			String iosFileName = cmd.getOptionValue('i');
			logger.log(Level.INFO, "Exporting as ios resource: " + iosFileName);
			
			Writer outputIosStream;
			try {
				outputIosStream = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(iosFileName), "UTF8"));
				Processor processorIos = new IosProcessor();
				processorIos.process(outputIosStream, map);
				logger.log(Level.INFO, "Exported with success");
			} catch (IOException e) {
				logger.log(Level.SEVERE, "Export failed...", e);
			}
			
		}
		
		logger.log(Level.INFO, "End of execution");
	}
	
	private static String getString(FormulaEvaluator evaluator, Cell cell) {
		
		if (cell == null) {
			return "";
		}
		
		CellValue cellValue = evaluator.evaluate(cell);
		
		if (cellValue == null) {
			return "";
		}
		
		switch (cellValue.getCellType()) {
		
			case Cell.CELL_TYPE_BOOLEAN:
				return cellValue.getBooleanValue() ? "true" : "false";
			case Cell.CELL_TYPE_NUMERIC:
				return String.valueOf(cellValue.getNumberValue());
			case Cell.CELL_TYPE_STRING:
				return cellValue.getStringValue();
			case Cell.CELL_TYPE_FORMULA: // not happening because we evaluate
			case Cell.CELL_TYPE_ERROR:
			case Cell.CELL_TYPE_BLANK:
			default:
				return "";
		}
		
	}

	private static void add(Map<String, List<KeyValuePair>> map,
			String groupBy, KeyValuePair keyValue) {

		List<KeyValuePair> list;
		if (!map.containsKey(groupBy)) {
			list = new ArrayList<KeyValuePair>();
			map.put(groupBy, list);
		} else {
			list = map.get(groupBy);
		}

		list.add(keyValue);
	}

	private static void help() {
		HelpFormatter formater = new HelpFormatter();
		formater.printHelp("Main", options);

		System.out.println("\nFormat of the Configuration file:");
		System.out.println("[");
		System.out.println("     {");
		System.out.println("         \"fileName\": (string) \"xls or xlsx file containing the wording. Mandatory.\",");
		System.out.println("         \"sheet\": (int) \"index of the sheet concerned. 0=first sheet. Default=0\", ");
		System.out.println("         \"rowStart\": (int) \"index of the starting row. 1=first row. Default=1\", ");
		System.out.println("         \"rowEnd\": (int) \"index of the last row. 1=first row. -1=all rows. Default=-1\", ");
		System.out.println("         \"columnKey\": (String) \"letter of the column containing the key. . Default='A'\", ");
		System.out.println("         \"columnValue\": (String) \"letter of the column containing the value. Default='B'\", ");
		System.out.println("         \"groupBy\": (String) \"letter of the column containing the group value. null=Do not group. Default=null\", ");
		System.out.println("     }, ...");
		System.out.println("]");
		System.out.println("");
		System.out.println("Example of how to use:");
		System.out.println("java -jar xlsToResource.jar -c config.json -a string.xml -i sample.strings");
		
		System.exit(0);

	}
	
	private static void printVersion() {
		System.out.println("V" + VERSION);
	}

}
