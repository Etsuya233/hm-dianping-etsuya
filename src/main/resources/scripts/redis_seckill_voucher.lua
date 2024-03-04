local userId = ARGV[1]
local voucherId = ARGV[2]
local orderId = ARGV[3]

-- 检查库存
local stockKey = 'seckill:stock:' .. voucherId
local stock = redis.call('get', stockKey)
if tonumber(stock) <= 0 then
    return 1
end

-- 检查一人一单
local setKey = 'seckill:order:' .. voucherId
local isMember = redis.call('sismember', setKey, userId)
if isMember == 1 then
    return 2
end

-- 保存数据
redis.call('decr', stockKey)
redis.call('sadd', setKey, userId)
-- xadd stream.orders * k1 v1 k2 v2
redis.call('xadd', 'stream.orders', '*', 'userId', userId, 'voucherId', voucherId, 'id', orderId)

return 0
