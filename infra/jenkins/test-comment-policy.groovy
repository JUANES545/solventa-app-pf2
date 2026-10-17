def policy = new GroovyClassLoader().parseClass(new File(args[0]))
def base = [id: 12L, body: '/test-jenkins', createdAt: 1000L, updatedAt: 1000L,
            human: true, permission: 'WRITE', open: true, origin: true, target: 'develop']
assert policy.eligible(base, 900L, [])
assert policy.canSchedule(false, false)
for (busy in [[true, false], [false, true], [true, true]]) {
    assert !policy.canSchedule(busy[0], busy[1])
    assert policy.eligible(base, 900L, []) // La solicitud sigue pendiente, no consumida.
}
assert policy.eligible(base + [body: ' /test-jenkins\n'], 900L, [])
assert !policy.eligible(base, 1001L, [])
assert !policy.eligible(base, 900L, [12L])
for (change in [[body: 'Please /test-jenkins'], [body: '/test-jenkins\nextra'],
                [body: '/test-jenkins; echo x'], [updatedAt: 1001L], [human: false],
                [permission: 'READ'], [permission: null], [open: false],
                [origin: false], [target: 'other'], [id: null]]) {
    assert !policy.eligible(base + change, 900L, []) : change.toString()
}
for (permission in ['ADMIN', 'WRITE', 'MAINTAIN']) {
    assert policy.eligible(base + [permission: permission], 900L, [])
}
assert policy.eligible(base + [target: 'master'], 900L, [])
println 'Comment authorization policy tests passed.'
