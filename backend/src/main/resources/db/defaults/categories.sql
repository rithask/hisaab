-- The built-in categories and their auto-filing rules, as V1__init.sql creates them. Used by Settings → Reset
-- to put the default categories back. Keep in step with any migration that changes the built-in ones.

insert into category (name, kind, color, system) values
    ('Food & Dining', 'EXPENSE', '#f97316', false),
    ('Groceries', 'EXPENSE', '#84cc16', false),
    ('Shopping', 'EXPENSE', '#ec4899', false),
    ('Transport', 'EXPENSE', '#0ea5e9', false),
    ('Fuel', 'EXPENSE', '#eab308', false),
    ('Bills & Utilities', 'EXPENSE', '#8b5cf6', false),
    ('Rent', 'EXPENSE', '#6366f1', false),
    ('Entertainment', 'EXPENSE', '#f43f5e', false),
    ('Health', 'EXPENSE', '#14b8a6', false),
    ('Travel', 'EXPENSE', '#06b6d4', false),
    ('Education', 'EXPENSE', '#a855f7', false),
    ('Subscriptions', 'EXPENSE', '#d946ef', false),
    ('Investments', 'EXPENSE', '#22c55e', false),
    ('Fees & Charges', 'EXPENSE', '#ef4444', false),
    ('Other Expense', 'EXPENSE', '#64748b', false),
    ('Salary', 'INCOME', '#22c55e', false),
    ('Interest', 'INCOME', '#10b981', false),
    ('Refund & Cashback', 'INCOME', '#0ea5e9', false),
    ('Gift', 'INCOME', '#f59e0b', false),
    ('Other Income', 'INCOME', '#64748b', false);

insert into category_rule (match_type, pattern, category_id, source, priority)
select 'CONTAINS', p.pattern, c.id, 'SYSTEM', 0
from (values
    ('swiggy instamart', 'Groceries'),
    ('instamart', 'Groceries'),
    ('blinkit', 'Groceries'),
    ('zepto', 'Groceries'),
    ('bigbasket', 'Groceries'),
    ('swiggy', 'Food & Dining'),
    ('zomato', 'Food & Dining'),
    ('eternal', 'Food & Dining'),
    ('amazon', 'Shopping'),
    ('flipkart', 'Shopping'),
    ('myntra', 'Shopping'),
    ('uber', 'Transport'),
    ('ola', 'Transport'),
    ('rapido', 'Transport'),
    ('irctc', 'Travel'),
    ('makemytrip', 'Travel'),
    ('indigo', 'Travel'),
    ('netflix', 'Subscriptions'),
    ('spotify', 'Subscriptions'),
    ('youtube', 'Subscriptions'),
    ('hotstar', 'Subscriptions'),
    ('airtel', 'Bills & Utilities'),
    ('jio', 'Bills & Utilities'),
    ('electricity', 'Bills & Utilities'),
    ('kseb', 'Bills & Utilities'),
    ('zerodha', 'Investments'),
    ('indian clearing corp', 'Investments'),
    ('petrol', 'Fuel'),
    ('fuel', 'Fuel'),
    ('pharmacy', 'Health'),
    ('apollo', 'Health'),
    ('salary', 'Salary'),
    ('interest', 'Interest'),
    ('cashback', 'Refund & Cashback'),
    ('refund', 'Refund & Cashback')
) as p(pattern, category)
join category c on c.name = p.category;

-- longer, more specific patterns should win
update category_rule set priority = length(pattern) where source = 'SYSTEM';
