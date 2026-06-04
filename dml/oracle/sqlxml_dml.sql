INSERT INTO sqlxml_test (name, xml_data) VALUES ('简单XML', XMLTYPE('<root><item>value1</item></root>'));
INSERT INTO sqlxml_test (name, xml_data) VALUES ('复杂XML', XMLTYPE('<root><person><name>张三</name><age>30</age></person></root>'));
