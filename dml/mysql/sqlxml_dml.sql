INSERT INTO {{sqlxml_test}} (name, xml_data)
VALUES ('simple XML', '<root><item>value1</item></root>');
INSERT INTO {{sqlxml_test}} (name, xml_data)
VALUES ('complex XML', '<root><person><name>张三</name><age>30</age></person></root>');
