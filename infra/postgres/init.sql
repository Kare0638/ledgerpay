-- One database per service: each owns its schema, and mock-psp shares nothing with our books.
CREATE DATABASE mock_psp OWNER ledgerpay;
CREATE DATABASE notification OWNER ledgerpay;
