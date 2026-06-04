INSERT INTO blobclob_test (name, binary_data, text_data) VALUES ('二进制测试1', UTL_RAW.CAST_TO_RAW('Hello'), '这是一段文本数据');
INSERT INTO blobclob_test (name, binary_data, text_data) VALUES ('大文本测试', NULL, '长文本内容长文本内容长文本内容长文本内容');
