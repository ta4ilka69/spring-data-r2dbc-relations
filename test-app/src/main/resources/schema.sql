CREATE TABLE customers (
    id SERIAL PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    email VARCHAR(255) NOT NULL
);

CREATE TABLE products (
    id SERIAL PRIMARY KEY,
    title VARCHAR(255) NOT NULL,
    price DECIMAL(10, 2) NOT NULL
);

CREATE TABLE orders (
    id SERIAL PRIMARY KEY,
    customer_id BIGINT NOT NULL REFERENCES customers(id),
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE TABLE order_items (
    id SERIAL PRIMARY KEY,
    order_id BIGINT NOT NULL REFERENCES orders(id),
    product_id BIGINT NOT NULL REFERENCES products(id),
    quantity INT NOT NULL
);

-- Тестовые данные
INSERT INTO customers (name, email) VALUES 
('Иван Иванов', 'ivan@example.com'),
('Анна Смирнова', 'anna@example.com');

INSERT INTO products (title, price) VALUES 
('Ноутбук', 120000.00),
('Мышь', 3500.00),
('Клавиатура', 4200.00);

INSERT INTO orders (customer_id, created_at) VALUES 
(1, '2026-03-31 10:00:00'),
(2, '2026-03-31 11:30:00');

INSERT INTO order_items (order_id, product_id, quantity) VALUES 
(1, 1, 1), -- Иван купил 1 ноутбук
(1, 2, 2), -- Иван купил 2 мыши
(2, 3, 1); -- Анна купила 1 клавиатуру

-- Справочник с UUID-ключом: нужен, чтобы покрыть сценарий не-Long идентификатора.
CREATE TABLE tags (
    id UUID PRIMARY KEY,
    name VARCHAR(255) NOT NULL
);

-- ONE_TO_MANY с UUID-внешним ключом.
CREATE TABLE tag_audits (
    id SERIAL PRIMARY KEY,
    tag_id UUID NOT NULL REFERENCES tags(id),
    note VARCHAR(255) NOT NULL
);

INSERT INTO tags (id, name) VALUES
    ('11111111-1111-1111-1111-111111111111', 'electronics'),
    ('22222222-2222-2222-2222-222222222222', 'books');

INSERT INTO tag_audits (tag_id, note) VALUES
    ('11111111-1111-1111-1111-111111111111', 'created'),
    ('11111111-1111-1111-1111-111111111111', 'renamed'),
    ('22222222-2222-2222-2222-222222222222', 'created');
