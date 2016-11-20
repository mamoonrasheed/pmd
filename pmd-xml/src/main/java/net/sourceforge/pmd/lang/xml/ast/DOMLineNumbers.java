/**
 * BSD-style license; for more info see http://pmd.sourceforge.net/license.html
 */
package net.sourceforge.pmd.lang.xml.ast;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringUtils;
import org.w3c.dom.Document;
import org.w3c.dom.DocumentType;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.w3c.dom.ProcessingInstruction;

/**
 *
 */
class DOMLineNumbers {
    private final Document document;
    private String xmlString;
    private List<Integer> lines;

    public DOMLineNumbers(Document document, String xmlString) {
        this.document = document;
        this.xmlString = xmlString;
    }
    
    public void determine() {
        calculateLinesMap();
        determineLocation(document, 0);
    }
    private int determineLocation(Node n, int index) {
        int nextIndex = index;
        int nodeLength = 0;
        int textLength = 0;
        if (n.getNodeType() == Node.DOCUMENT_TYPE_NODE) {
            nextIndex = xmlString.indexOf("<!DOCTYPE", nextIndex);
            nodeLength = "<!DOCTYPE".length();
        } else if (n.getNodeType() == Node.COMMENT_NODE) {
            nextIndex = xmlString.indexOf("<!--", nextIndex);
        } else if (n.getNodeType() == Node.ELEMENT_NODE) {
            nextIndex = xmlString.indexOf("<" + n.getNodeName(), nextIndex);
            nodeLength = xmlString.indexOf(">", nextIndex) - nextIndex + 1;
        } else if (n.getNodeType() == Node.CDATA_SECTION_NODE) {
            nextIndex = xmlString.indexOf("<![CDATA[", nextIndex);
        } else if (n.getNodeType() == Node.PROCESSING_INSTRUCTION_NODE) {
            ProcessingInstruction pi = (ProcessingInstruction)n;
            nextIndex = xmlString.indexOf("<?" + pi.getTarget(), nextIndex);
        } else if (n.getNodeType() == Node.TEXT_NODE) {
            String te = unexpandEntities(n, n.getNodeValue(), true);
            int newIndex = xmlString.indexOf(te, nextIndex);
            if (newIndex == -1) {
                // try again without escaping the quotes
                te = unexpandEntities(n, n.getNodeValue(), false);
                newIndex = xmlString.indexOf(te, nextIndex);
            }
            if (newIndex > 0) {
                textLength = te.length();
                nextIndex = newIndex;
            }
        } else if (n.getNodeType() == Node.ENTITY_REFERENCE_NODE) {
            nextIndex = xmlString.indexOf("&" + n.getNodeName() + ";", nextIndex);
        }
        setBeginLocation(n, nextIndex);
        if (n.hasChildNodes()) {
            // next nodes begin after the current start tag
            nextIndex += nodeLength;
            NodeList childs = n.getChildNodes();
            for (int i = 0; i < childs.getLength(); i++) {
                nextIndex = determineLocation(childs.item(i), nextIndex);
            }
        }
        if (n.getNodeType() == Node.ELEMENT_NODE) {
            nextIndex += 2 + n.getNodeName().length() + 1; // </nodename>
        } else if (n.getNodeType() == Node.DOCUMENT_TYPE_NODE) {
            Node nextSibling = n.getNextSibling();
            if (nextSibling.getNodeType() == Node.ELEMENT_NODE) {
                nextIndex = xmlString.indexOf("<" + nextSibling.getNodeName(), nextIndex) - 1;
            } else if (nextSibling.getNodeType() == Node.COMMENT_NODE) {
                nextIndex = xmlString.indexOf("<!--", nextIndex);
            } else {
                nextIndex = xmlString.indexOf(">", nextIndex);
            }
        } else if (n.getNodeType() == Node.COMMENT_NODE) {
            nextIndex += 4 + 3; // <!-- and -->
            nextIndex += n.getNodeValue().length();
        } else if (n.getNodeType() == Node.TEXT_NODE) {
            nextIndex += textLength;
        } else if (n.getNodeType() == Node.CDATA_SECTION_NODE) {
            nextIndex += "<![CDATA[".length() + n.getNodeValue().length() + "]]>".length();
        } else if (n.getNodeType() == Node.PROCESSING_INSTRUCTION_NODE) {
            ProcessingInstruction pi = (ProcessingInstruction)n;
            nextIndex += "<?".length() + pi.getTarget().length() + "?>".length() + pi.getData().length();
        }
        setEndLocation(n, nextIndex - 1);
        return nextIndex;
    }

    private String unexpandEntities(Node n, String te, boolean withQuotes) {
        String result = te;
        DocumentType doctype = n.getOwnerDocument().getDoctype();
        // implicit entities
        result = result.replaceAll(Matcher.quoteReplacement("&"), "&amp;");
        result = result.replaceAll(Matcher.quoteReplacement("<"), "&lt;");
        result = result.replaceAll(Matcher.quoteReplacement(">"), "&gt;");
        if (withQuotes) {
            result = result.replaceAll(Matcher.quoteReplacement("\""), "&quot;");
            result = result.replaceAll(Matcher.quoteReplacement("'"), "&apos;");
        }

        if (doctype != null) {
            NamedNodeMap entities = doctype.getEntities();
            String internalSubset = doctype.getInternalSubset();
            if (internalSubset == null) {
                internalSubset = "";
            }
            for (int i = 0; i < entities.getLength(); i++) {
                Node item = entities.item(i);
                String entityName = item.getNodeName();
                Node firstChild = item.getFirstChild();
                if (firstChild != null) {
                    result = result.replaceAll(Matcher.quoteReplacement(firstChild.getNodeValue()), "&" + entityName + ";");
                } else {
                    Matcher m = Pattern.compile(Matcher.quoteReplacement("<!ENTITY " + entityName + " ") + "[']([^']*)[']>").matcher(internalSubset);
                    if (m.find()) {
                        result = result.replaceAll(Matcher.quoteReplacement(m.group(1)), "&" + entityName + ";");
                    }
                }
            }
        }
        return result;
    }
    private void setBeginLocation(Node n, int index) {
        if (n != null) {
            int line = toLine(index);
            n.setUserData(XmlNode.BEGIN_LINE, line, null);
            n.setUserData(XmlNode.BEGIN_COLUMN, toColumn(line, index), null);
        }
    }
    private void setEndLocation(Node n, int index) {
        if (n != null) {
            int line = toLine(index);
            n.setUserData(XmlNode.END_LINE, line, null);
            n.setUserData(XmlNode.END_COLUMN, toColumn(line, index), null);
        }
    }
    
    /**
     * Calculates a list with the file offsets for each line.
     */
    private void calculateLinesMap() {
        lines = new ArrayList<>();

        int index = -1;
        int count = StringUtils.countMatches(xmlString, "\n");
        for (int line = 1; line <= count; line++) {
            lines.add(index + 1);
            index = xmlString.indexOf("\n", index + 1); // fast forward till end of current line
        }
        lines.add(index + 1);
    }

    private int toLine(int index) {
        int low = 0;
        int high = lines.size() - 1;

        // binary search the best match
        while (low <= high) {
            int middle = (low + high) / 2;
            int middleStart = lines.get(middle);
            if (middleStart == index) {
                return middle + 1; // found
            }

            if (middleStart > index) {
                high = middle - 1;
            } else {
                low = middle + 1;
            }
        }

        return low; // not found or last checked line, which is the best match;
    }

    private int toColumn(int line, int index) {
        if (line <= 0) {
            // line couldn't be determined
            return 0;
        }

        Integer lineStart = lines.get(line - 1);
        if (lineStart == null) {
            lineStart = lines.get(lines.size() - 1);
        }
        int column = index - lineStart;
        return column + 1;
    }
}
